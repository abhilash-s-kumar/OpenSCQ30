//! In-memory fake Nothing earbuds, used for demo mode and tests.

use std::{
    collections::HashSet,
    sync::{Arc, Mutex},
};

use async_trait::async_trait;
use macaddr::MacAddr6;
use openscq30_i18n::Translate;
use tokio::sync::{mpsc, watch};

use crate::{
    api::connection::{self, ConnectionDescriptor, ConnectionStatus, RfcommBackend, RfcommConnection},
    connection::RfcommServiceSelectionStrategy,
    devices::{
        DeviceModel,
        nothing::packet::{self, FrameReader, command, control},
    },
};

const RESPONSE_COMMAND_MASK: u16 = 0x7fff;

pub struct DemoNothingBackend {
    model: DeviceModel,
}

impl DemoNothingBackend {
    pub fn new(model: DeviceModel) -> Self {
        Self { model }
    }
}

#[async_trait]
impl RfcommBackend for DemoNothingBackend {
    async fn devices(&self) -> connection::Result<HashSet<ConnectionDescriptor>> {
        Ok(HashSet::from([ConnectionDescriptor {
            name: self.model.translate(),
            mac_address: self.model.demo_mac_address(),
        }]))
    }

    async fn connect(
        &self,
        _mac_address: MacAddr6,
        _service_selection_strategy: RfcommServiceSelectionStrategy,
    ) -> connection::Result<Arc<dyn RfcommConnection + Send + Sync>> {
        Ok(Arc::new(DemoNothingConnection::new()))
    }
}

struct DemoNothingConnection {
    _status_sender: watch::Sender<ConnectionStatus>,
    status_receiver: watch::Receiver<ConnectionStatus>,
    sender: mpsc::Sender<Vec<u8>>,
    receiver: Mutex<Option<mpsc::Receiver<Vec<u8>>>>,
    audio_mode: Mutex<u8>,
}

impl DemoNothingConnection {
    fn new() -> Self {
        let (status_sender, status_receiver) = watch::channel(ConnectionStatus::Connected);
        let (sender, receiver) = mpsc::channel(100);
        Self {
            _status_sender: status_sender,
            status_receiver,
            sender,
            receiver: Mutex::new(Some(receiver)),
            audio_mode: Mutex::new(0x01),
        }
    }

    fn response(command: u16, payload: &[u8]) -> Vec<u8> {
        packet::encode(control::DEFAULT, command & RESPONSE_COMMAND_MASK, 0, payload)
    }
}

#[async_trait]
impl RfcommConnection for DemoNothingConnection {
    async fn write(&self, data: &[u8]) -> connection::Result<()> {
        for frame in FrameReader::default().push(data) {
            let response = match frame.command {
                command::BATTERY_STATUS => Self::response(
                    command::BATTERY_STATUS,
                    // left 82%, right 79%, case 64% charging
                    &[3, 0x02, 82, 0x03, 79, 0x04, 0x80 | 64],
                ),
                command::AUDIO_MODE_STATUS => {
                    let mode = *self.audio_mode.lock().unwrap();
                    Self::response(command::AUDIO_MODE_STATUS, &[0x01, mode, 0x00])
                }
                command::SET_AUDIO_MODE => {
                    if let Some(mode) = frame.payload.get(1) {
                        *self.audio_mode.lock().unwrap() = *mode;
                    }
                    Self::response(command::SET_AUDIO_MODE, &[])
                }
                other => Self::response(other, &[]),
            };
            let _ = self.sender.send(response).await;
        }
        Ok(())
    }

    fn read_channel(&self) -> mpsc::Receiver<Vec<u8>> {
        self.receiver
            .lock()
            .unwrap()
            .take()
            .expect("read_channel should only be called once")
    }

    fn connection_status(&self) -> watch::Receiver<ConnectionStatus> {
        self.status_receiver.clone()
    }
}
