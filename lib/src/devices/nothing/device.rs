use std::{
    borrow::Cow,
    sync::{Arc, Mutex},
    time::Duration,
};

use async_trait::async_trait;
use macaddr::MacAddr6;
use openscq30_i18n_macros::Translate;
use strum::{EnumString, IntoStaticStr, VariantArray};
use tokio::{sync::watch, task::JoinHandle};
use uuid::Uuid;

use crate::{
    api::{
        connection::{ConnectionDescriptor, ConnectionStatus, RfcommBackend, RfcommConnection},
        device::{self, OpenSCQ30Device, OpenSCQ30DeviceRegistry},
        settings::{CategoryId, Select, Setting, SettingId, Value},
    },
    connection::RfcommServiceSelectionStrategy,
    devices::{
        DeviceModel,
        nothing::packet::{self, Frame, FrameReader, command, control},
    },
    i18n::fl,
};

/// RFCOMM service used by Nothing earbuds for control (not the audio or Fast Pair channels).
pub const RFCOMM_UUID: Uuid = uuid::uuid!("aeac4a03-dff5-498f-843a-34487cf133eb");

const INITIAL_STATE_TIMEOUT: Duration = Duration::from_secs(3);

const BATTERY_LEFT: u8 = 0x02;
const BATTERY_RIGHT: u8 = 0x03;
const BATTERY_CASE: u8 = 0x04;
const BATTERY_LEVEL_MASK: u8 = 0x7f;
const BATTERY_CHARGING_MASK: u8 = 0x80;

/// Per-model protocol differences.
#[derive(Debug, Clone, Copy)]
pub struct NothingDeviceConfig {
    /// Ear (1) expects an incrementing sequence number (and control bit 0x40); later models don't.
    pub increment_sequence: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, IntoStaticStr, EnumString, VariantArray, Translate)]
enum AmbientSoundMode {
    NoiseCanceling,
    Transparency,
    Normal,
}

/// Noise canceling strength. Ear (2) supports "high" and "light".
#[derive(Debug, Clone, Copy, PartialEq, Eq, IntoStaticStr, EnumString, VariantArray, Translate)]
enum NoiseCancelingStrength {
    Strong,
    Weak,
}

/// Raw audio mode byte (second payload byte of audio mode packets).
mod audio_mode_byte {
    pub const ANC_HIGH: u8 = 0x01;
    pub const ANC_LIGHT: u8 = 0x03;
    pub const OFF: u8 = 0x05;
    pub const TRANSPARENCY: u8 = 0x07;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Battery {
    percent: u8,
    is_charging: bool,
}

#[derive(Debug, Default)]
struct NothingState {
    left: Option<Battery>,
    right: Option<Battery>,
    case: Option<Battery>,
    ambient_sound_mode: Option<AmbientSoundMode>,
    /// Remembered even while not in noise canceling mode, so switching back restores it.
    noise_canceling_strength: Option<NoiseCancelingStrength>,
}

impl NothingState {
    fn apply(&mut self, frame: &Frame) -> bool {
        if !frame.is_ok() {
            tracing::debug!("nothing: ignoring non-ok frame {frame:?}");
            return false;
        }
        match frame.command {
            command::BATTERY_NOTIFICATION | command::BATTERY_STATUS => self.apply_battery(&frame.payload),
            command::AUDIO_MODE_STATUS => self.apply_audio_mode(&frame.payload),
            command::CASE_CLOSED => false,
            _ => {
                tracing::debug!("nothing: unhandled frame {frame:?}");
                false
            }
        }
    }

    /// Payload: count, then `count` pairs of (battery id, level | charging bit). Levels are 0-100.
    fn apply_battery(&mut self, payload: &[u8]) -> bool {
        let Some((&count, pairs)) = payload.split_first() else {
            return false;
        };
        let mut changed = false;
        for pair in pairs.chunks_exact(2).take(usize::from(count)) {
            let battery = Battery {
                percent: (pair[1] & BATTERY_LEVEL_MASK).min(100),
                is_charging: pair[1] & BATTERY_CHARGING_MASK != 0,
            };
            let slot = match pair[0] {
                BATTERY_LEFT => &mut self.left,
                BATTERY_RIGHT => &mut self.right,
                BATTERY_CASE => &mut self.case,
                other => {
                    tracing::debug!("nothing: unknown battery id {other}");
                    continue;
                }
            };
            if *slot != Some(battery) {
                *slot = Some(battery);
                changed = true;
            }
        }
        changed
    }

    fn apply_audio_mode(&mut self, payload: &[u8]) -> bool {
        let Some(&mode) = payload.get(1) else {
            return false;
        };
        let (ambient, strength) = match mode {
            audio_mode_byte::ANC_HIGH => (AmbientSoundMode::NoiseCanceling, Some(NoiseCancelingStrength::Strong)),
            audio_mode_byte::ANC_LIGHT => (AmbientSoundMode::NoiseCanceling, Some(NoiseCancelingStrength::Weak)),
            audio_mode_byte::OFF => (AmbientSoundMode::Normal, None),
            audio_mode_byte::TRANSPARENCY => (AmbientSoundMode::Transparency, None),
            other => {
                tracing::warn!("nothing: unknown audio mode {other:#04x}, payload {payload:?}");
                return false;
            }
        };
        let before = (self.ambient_sound_mode, self.noise_canceling_strength);
        self.ambient_sound_mode = Some(ambient);
        if strength.is_some() {
            self.noise_canceling_strength = strength;
        }
        before != (self.ambient_sound_mode, self.noise_canceling_strength)
    }

    fn audio_mode_byte(&self) -> u8 {
        match self.ambient_sound_mode {
            Some(AmbientSoundMode::NoiseCanceling) => match self.noise_canceling_strength {
                Some(NoiseCancelingStrength::Weak) => audio_mode_byte::ANC_LIGHT,
                _ => audio_mode_byte::ANC_HIGH,
            },
            Some(AmbientSoundMode::Transparency) => audio_mode_byte::TRANSPARENCY,
            Some(AmbientSoundMode::Normal) | None => audio_mode_byte::OFF,
        }
    }
}

pub struct NothingDeviceRegistry {
    backend: Arc<dyn RfcommBackend + Send + Sync>,
    model: DeviceModel,
    config: NothingDeviceConfig,
}

impl NothingDeviceRegistry {
    pub fn new(
        backend: Arc<dyn RfcommBackend + Send + Sync>,
        model: DeviceModel,
        config: NothingDeviceConfig,
    ) -> Self {
        Self { backend, model, config }
    }
}

#[async_trait]
impl OpenSCQ30DeviceRegistry for NothingDeviceRegistry {
    async fn devices(&self) -> device::Result<Vec<ConnectionDescriptor>> {
        self.backend
            .devices()
            .await
            .map(|it| it.into_iter().collect())
            .map_err(Into::into)
    }

    async fn connect(&self, mac_address: MacAddr6) -> device::Result<Arc<dyn OpenSCQ30Device + Send + Sync>> {
        let connection = self
            .backend
            .connect(mac_address, RfcommServiceSelectionStrategy::Constant(RFCOMM_UUID))
            .await?;
        Ok(Arc::new(NothingDevice::new(connection, self.model, self.config).await?))
    }
}

pub struct NothingDevice {
    connection: Arc<dyn RfcommConnection + Send + Sync>,
    model: DeviceModel,
    config: NothingDeviceConfig,
    state: Arc<Mutex<NothingState>>,
    changes: watch::Sender<()>,
    sequence: Mutex<u8>,
    reader: JoinHandle<()>,
}

impl Drop for NothingDevice {
    fn drop(&mut self) {
        self.reader.abort();
    }
}

impl NothingDevice {
    async fn new(
        connection: Arc<dyn RfcommConnection + Send + Sync>,
        model: DeviceModel,
        config: NothingDeviceConfig,
    ) -> device::Result<Self> {
        let state = Arc::new(Mutex::new(NothingState::default()));
        let changes = watch::channel(()).0;

        let reader = {
            let mut incoming = connection.read_channel();
            let state = state.clone();
            let changes = changes.clone();
            tokio::spawn(async move {
                let mut reader = FrameReader::default();
                while let Some(bytes) = incoming.recv().await {
                    let mut changed = false;
                    {
                        let mut state = state.lock().unwrap();
                        for frame in reader.push(&bytes) {
                            tracing::debug!("nothing: received {frame:?}");
                            changed |= state.apply(&frame);
                        }
                    }
                    if changed {
                        changes.send_replace(());
                    }
                }
            })
        };

        let device = Self {
            connection,
            model,
            config,
            state,
            changes,
            sequence: Mutex::new(0),
            reader,
        };

        // Request initial state, then wait (briefly) until both battery and audio mode are known
        let mut change_receiver = device.changes.subscribe();
        device.send(control::BATTERY_REQUEST, command::BATTERY_STATUS, &[]).await?;
        device.send(control::DEFAULT, command::AUDIO_MODE_STATUS, &[]).await?;
        let _ = tokio::time::timeout(INITIAL_STATE_TIMEOUT, async {
            loop {
                {
                    let state = device.state.lock().unwrap();
                    if state.ambient_sound_mode.is_some() && (state.left.is_some() || state.right.is_some()) {
                        break;
                    }
                }
                if change_receiver.changed().await.is_err() {
                    break;
                }
            }
        })
        .await;

        Ok(device)
    }

    async fn send(&self, control: u16, command: u16, payload: &[u8]) -> device::Result<()> {
        let (control, sequence) = {
            let mut sequence = self.sequence.lock().unwrap();
            let current = *sequence;
            if self.config.increment_sequence {
                *sequence = if current.wrapping_add(1) == 0xfd { 0 } else { current.wrapping_add(1) };
                (control | 0x40, current)
            } else {
                (control, current)
            }
        };
        let bytes = packet::encode(control, command, sequence, payload);
        tracing::debug!("nothing: sending {bytes:02x?}");
        self.connection.write(&bytes).await.map_err(Into::into)
    }

    fn battery_setting(battery: Option<Battery>) -> Option<Setting> {
        battery.map(|battery| Setting::Information {
            value: format!("{}/100", battery.percent),
            translated_value: fl!("percent", percent = i32::from(battery.percent)),
        })
    }

    fn charging_setting(battery: Option<Battery>) -> Option<Setting> {
        battery.map(|battery| Setting::Information {
            value: battery.is_charging.to_string(),
            translated_value: if battery.is_charging { fl!("charging") } else { fl!("not-charging") },
        })
    }
}

#[async_trait]
impl OpenSCQ30Device for NothingDevice {
    fn connection_status(&self) -> watch::Receiver<ConnectionStatus> {
        self.connection.connection_status()
    }

    fn model(&self) -> DeviceModel {
        self.model
    }

    fn categories(&self) -> Vec<CategoryId> {
        vec![CategoryId::SoundModes, CategoryId::DeviceInformation]
    }

    fn settings_in_category(&self, category_id: &CategoryId) -> Vec<SettingId> {
        match category_id {
            CategoryId::SoundModes => vec![SettingId::AmbientSoundMode, SettingId::NoiseCancelingMode],
            CategoryId::DeviceInformation => vec![
                SettingId::BatteryLevelLeft,
                SettingId::IsChargingLeft,
                SettingId::BatteryLevelRight,
                SettingId::IsChargingRight,
                SettingId::CaseBatteryLevel,
            ],
            _ => Vec::new(),
        }
    }

    fn setting(&self, setting_id: &SettingId) -> Option<Setting> {
        let state = self.state.lock().unwrap();
        match setting_id {
            SettingId::AmbientSoundMode => state.ambient_sound_mode.map(|mode| Setting::Select {
                setting: Select::from_enum(AmbientSoundMode::VARIANTS.iter().copied()),
                value: Cow::Borrowed(mode.into()),
            }),
            SettingId::NoiseCancelingMode => Some(Setting::Select {
                setting: Select::from_enum(NoiseCancelingStrength::VARIANTS.iter().copied()),
                value: Cow::Borrowed(state.noise_canceling_strength.unwrap_or(NoiseCancelingStrength::Strong).into()),
            }),
            SettingId::BatteryLevelLeft => Self::battery_setting(state.left),
            SettingId::IsChargingLeft => Self::charging_setting(state.left),
            SettingId::BatteryLevelRight => Self::battery_setting(state.right),
            SettingId::IsChargingRight => Self::charging_setting(state.right),
            SettingId::CaseBatteryLevel => Self::battery_setting(state.case),
            _ => None,
        }
    }

    fn watch_for_changes(&self) -> watch::Receiver<()> {
        self.changes.subscribe()
    }

    async fn set_setting_values(&self, setting_values: Vec<(SettingId, Value)>) -> device::Result<()> {
        let mode_byte = {
            let mut state = self.state.lock().unwrap();
            let mut relevant = false;
            for (setting_id, value) in &setting_values {
                match setting_id {
                    SettingId::AmbientSoundMode => {
                        let mode: AmbientSoundMode = parse_enum(value)?;
                        state.ambient_sound_mode = Some(mode);
                        relevant = true;
                    }
                    SettingId::NoiseCancelingMode => {
                        let strength: NoiseCancelingStrength = parse_enum(value)?;
                        state.noise_canceling_strength = Some(strength);
                        // Only affects the earbuds while noise canceling is active
                        relevant |= state.ambient_sound_mode == Some(AmbientSoundMode::NoiseCanceling);
                    }
                    other => tracing::warn!("nothing: setting {other:?} is not supported"),
                }
            }
            relevant.then(|| state.audio_mode_byte())
        };
        // Optimistic update, so the UI reflects the change immediately
        self.changes.send_replace(());

        if let Some(mode_byte) = mode_byte {
            self.send(control::DEFAULT, command::SET_AUDIO_MODE, &[0x01, mode_byte, 0x00])
                .await?;
            // Confirm what the earbuds actually applied
            self.send(control::DEFAULT, command::AUDIO_MODE_STATUS, &[]).await?;
        }
        Ok(())
    }
}

fn parse_enum<T: std::str::FromStr>(value: &Value) -> device::Result<T>
where
    T::Err: std::error::Error + Send + Sync + 'static,
{
    let text = value.try_as_str().map_err(device::Error::other)?;
    T::from_str(text).map_err(device::Error::other)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_battery_payload() {
        let mut state = NothingState::default();
        // 3 batteries: left 80%, right 81% charging, case 100% charging
        assert!(state.apply_battery(&[3, BATTERY_LEFT, 80, BATTERY_RIGHT, 0x80 | 81, BATTERY_CASE, 0x80 | 100]));
        assert_eq!(state.left, Some(Battery { percent: 80, is_charging: false }));
        assert_eq!(state.right, Some(Battery { percent: 81, is_charging: true }));
        assert_eq!(state.case, Some(Battery { percent: 100, is_charging: true }));
        // Same values again: no change
        assert!(!state.apply_battery(&[1, BATTERY_LEFT, 80]));
    }

    #[test]
    fn parses_audio_modes_and_remembers_strength() {
        let mut state = NothingState::default();
        assert!(state.apply_audio_mode(&[1, audio_mode_byte::ANC_LIGHT, 0]));
        assert_eq!(state.ambient_sound_mode, Some(AmbientSoundMode::NoiseCanceling));
        assert_eq!(state.noise_canceling_strength, Some(NoiseCancelingStrength::Weak));

        assert!(state.apply_audio_mode(&[1, audio_mode_byte::TRANSPARENCY, 0]));
        assert_eq!(state.ambient_sound_mode, Some(AmbientSoundMode::Transparency));
        // Strength survives leaving noise canceling
        assert_eq!(state.noise_canceling_strength, Some(NoiseCancelingStrength::Weak));

        state.ambient_sound_mode = Some(AmbientSoundMode::NoiseCanceling);
        assert_eq!(state.audio_mode_byte(), audio_mode_byte::ANC_LIGHT);
    }

    #[test]
    fn ignores_unknown_audio_mode() {
        let mut state = NothingState::default();
        assert!(!state.apply_audio_mode(&[1, 0x42, 0]));
        assert_eq!(state.ambient_sound_mode, None);
    }
}
