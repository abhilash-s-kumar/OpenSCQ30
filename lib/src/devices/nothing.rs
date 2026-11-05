//! Nothing earbuds. Protocol details were learned from Gadgetbridge's Nothing support
//! (https://codeberg.org/Freeyourgadget/Gadgetbridge); this is an independent implementation.

use std::sync::Arc;

use crate::{api::connection::RfcommBackend, devices::DeviceModel};

mod demo;
mod device;
mod packet;

pub use device::{NothingDeviceConfig, NothingDeviceRegistry};

/// Ear (1) increments the sequence number; Ear (2) and later don't. Only Ear (2) is supported so far.
pub fn config(_model: DeviceModel) -> NothingDeviceConfig {
    NothingDeviceConfig {
        increment_sequence: false,
    }
}

pub fn device_registry(backend: Arc<dyn RfcommBackend + Send + Sync>, model: DeviceModel) -> NothingDeviceRegistry {
    NothingDeviceRegistry::new(backend, model, config(model))
}

pub fn demo_device_registry(model: DeviceModel) -> NothingDeviceRegistry {
    NothingDeviceRegistry::new(Arc::new(demo::DemoNothingBackend::new(model)), model, config(model))
}
