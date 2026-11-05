//! Framing for the Nothing earbuds control protocol (RFCOMM).
//!
//! Frame layout, all multi-byte fields little endian:
//!
//! | offset | size | field                                                        |
//! |--------|------|--------------------------------------------------------------|
//! | 0      | 1    | start of frame, always 0x55                                  |
//! | 1      | 2    | control: bits 8-11 device type (1 = TWS), 0x20 = has CRC,    |
//! |        |      | bits 0-4 response code (0 = ok)                              |
//! | 3      | 2    | command                                                      |
//! | 5      | 2    | payload length                                               |
//! | 7      | 1    | frame sequence number                                        |
//! | 8      | n    | payload                                                      |
//! | 8+n    | 2    | CRC-16/MODBUS over bytes 0..8+n, only if control has 0x20    |
//!
//! Responses and notifications carry the command with bit 15 cleared, so `command | 0x8000` gives the
//! request command they correspond to.

pub const START_OF_FRAME: u8 = 0x55;
const HEADER_LENGTH: usize = 8;
const CONTROL_HAS_CRC: u16 = 0x20;
const CONTROL_RESPONSE_CODE_MASK: u16 = 0x1f;
const CONTROL_DEVICE_TYPE_MASK: u16 = 0x0f00;
const DEVICE_TYPE_TWS: u16 = 1;
const REQUEST_COMMAND_BIT: u16 = 0x8000;
/// Sanity limit so garbage can't make us wait for a huge payload.
const MAX_PAYLOAD_LENGTH: usize = 4096;

pub mod command {
    /// Unsolicited battery update
    pub const BATTERY_NOTIFICATION: u16 = 0xe001;
    /// Battery request and its response
    pub const BATTERY_STATUS: u16 = 0xc007;
    /// Audio (ANC) mode request and its response
    pub const AUDIO_MODE_STATUS: u16 = 0xc01e;
    /// Set audio mode
    pub const SET_AUDIO_MODE: u16 = 0xf00f;
    /// Sent twice when the case is closed with earbuds in
    pub const CASE_CLOSED: u16 = 0xe002;
}

/// Control values used by Gadgetbridge for outbound requests.
pub mod control {
    pub const DEFAULT: u16 = 0x0120;
    pub const BATTERY_REQUEST: u16 = 0x5120;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub control: u16,
    /// Normalized so responses match the request command (bit 15 set).
    pub command: u16,
    pub sequence: u8,
    pub payload: Vec<u8>,
}

impl Frame {
    pub fn is_ok(&self) -> bool {
        self.control & CONTROL_RESPONSE_CODE_MASK == 0
    }

    pub fn is_tws(&self) -> bool {
        (self.control & CONTROL_DEVICE_TYPE_MASK) >> 8 == DEVICE_TYPE_TWS
    }
}

pub fn encode(control: u16, command: u16, sequence: u8, payload: &[u8]) -> Vec<u8> {
    let mut bytes = Vec::with_capacity(HEADER_LENGTH + payload.len() + 2);
    bytes.push(START_OF_FRAME);
    bytes.extend_from_slice(&control.to_le_bytes());
    bytes.extend_from_slice(&command.to_le_bytes());
    bytes.extend_from_slice(&(payload.len() as u16).to_le_bytes());
    bytes.push(sequence);
    bytes.extend_from_slice(payload);
    if control & CONTROL_HAS_CRC != 0 {
        let crc = crc16_modbus(&bytes);
        bytes.extend_from_slice(&crc.to_le_bytes());
    }
    bytes
}

pub fn crc16_modbus(data: &[u8]) -> u16 {
    let mut crc: u16 = 0xffff;
    for byte in data {
        crc ^= u16::from(*byte);
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xa001
            } else {
                crc >> 1
            };
        }
    }
    crc
}

/// Reassembles frames from an RFCOMM byte stream, where one read may hold a partial frame or several frames.
#[derive(Default)]
pub struct FrameReader {
    buffer: Vec<u8>,
}

impl FrameReader {
    pub fn push(&mut self, bytes: &[u8]) -> Vec<Frame> {
        self.buffer.extend_from_slice(bytes);
        let mut frames = Vec::new();
        loop {
            // Resync: drop anything before the next start of frame
            match self.buffer.iter().position(|b| *b == START_OF_FRAME) {
                Some(0) => (),
                Some(index) => {
                    tracing::debug!("nothing: skipping {index} bytes to resync");
                    self.buffer.drain(..index);
                }
                None => {
                    self.buffer.clear();
                    break;
                }
            }
            if self.buffer.len() < HEADER_LENGTH {
                break;
            }
            let control = u16::from_le_bytes([self.buffer[1], self.buffer[2]]);
            let command = u16::from_le_bytes([self.buffer[3], self.buffer[4]]);
            let length = usize::from(u16::from_le_bytes([self.buffer[5], self.buffer[6]]));
            let sequence = self.buffer[7];
            if length > MAX_PAYLOAD_LENGTH {
                // Not a real frame, skip this start byte
                self.buffer.drain(..1);
                continue;
            }
            let payload_end = HEADER_LENGTH + length;
            if self.buffer.len() < payload_end {
                break;
            }
            let payload = self.buffer[HEADER_LENGTH..payload_end].to_vec();

            // Some frames that claim a CRC arrive without one, so only consume it if it checks out.
            let mut frame_end = payload_end;
            if control & CONTROL_HAS_CRC != 0 && self.buffer.len() >= payload_end + 2 {
                let expected = crc16_modbus(&self.buffer[..payload_end]);
                let actual = u16::from_le_bytes([self.buffer[payload_end], self.buffer[payload_end + 1]]);
                if expected == actual {
                    frame_end = payload_end + 2;
                }
            }
            self.buffer.drain(..frame_end);
            frames.push(Frame {
                control,
                command: command | REQUEST_COMMAND_BIT,
                sequence,
                payload,
            });
        }
        frames
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn crc_matches_modbus_check_value() {
        // Standard CRC-16/MODBUS check value for "123456789"
        assert_eq!(crc16_modbus(b"123456789"), 0x4b37);
    }

    #[test]
    fn encode_then_decode_roundtrip() {
        let bytes = encode(control::DEFAULT, command::SET_AUDIO_MODE, 0, &[1, 7, 0]);
        let frames = FrameReader::default().push(&bytes);
        assert_eq!(
            frames,
            vec![Frame {
                control: control::DEFAULT,
                command: command::SET_AUDIO_MODE,
                sequence: 0,
                payload: vec![1, 7, 0],
            }]
        );
    }

    #[test]
    fn decodes_ack_without_crc() {
        // Ack for set audio mode as captured by Gadgetbridge: 55 20 01 0F 70 00 00 00
        let frames = FrameReader::default().push(&[0x55, 0x20, 0x01, 0x0f, 0x70, 0x00, 0x00, 0x00]);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].command, command::SET_AUDIO_MODE);
        assert!(frames[0].is_ok());
        assert!(frames[0].is_tws());
    }

    #[test]
    fn handles_split_and_concatenated_frames() {
        let a = encode(control::DEFAULT, 0x401e, 1, &[1, 1, 0]);
        let b = encode(control::DEFAULT, 0x6001, 2, &[3, 2, 0x50, 3, 0x51, 4, 0xe4]);
        let mut stream = a.clone();
        stream.extend_from_slice(&b);
        let mut reader = FrameReader::default();
        let mut frames = reader.push(&stream[..5]);
        assert!(frames.is_empty());
        frames.extend(reader.push(&stream[5..]));
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].command, command::AUDIO_MODE_STATUS);
        assert_eq!(frames[1].command, command::BATTERY_NOTIFICATION);
        assert_eq!(frames[1].payload, vec![3, 2, 0x50, 3, 0x51, 4, 0xe4]);
    }

    #[test]
    fn resyncs_after_garbage() {
        let mut bytes = vec![0x01, 0x02, 0x03];
        bytes.extend(encode(control::DEFAULT, 0x401e, 0, &[1, 5, 0]));
        let frames = FrameReader::default().push(&bytes);
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].payload, vec![1, 5, 0]);
    }
}
