use std::sync::{Arc, OnceLock};

use iroh::endpoint::presets::N0;
use iroh::{Endpoint, EndpointId, SecretKey};
use thiserror::Error;

#[derive(Debug, Error)]
pub enum FarmError {
    #[error("internal error")]
    Internal,
    #[error("invalid input")]
    InvalidInput,
}

fn rt() -> &'static tokio::runtime::Runtime {
    static RT: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .thread_name("farm-iroh")
            .build()
            .expect("farm-iroh tokio runtime")
    })
}

pub struct FarmEndpoint {
    endpoint: Endpoint,
}

fn map_err<E: std::fmt::Display>(e: E) -> FarmError {
    eprintln!("farm-iroh: {e}");
    FarmError::Internal
}

fn map_input(msg: &str) -> FarmError {
    eprintln!("farm-iroh input: {msg}");
    FarmError::InvalidInput
}

pub fn connect(secret_key: Option<Vec<u8>>) -> Result<Arc<FarmEndpoint>, FarmError> {
    let sk = match secret_key {
        Some(bytes) => {
            let arr: [u8; 32] = bytes
                .try_into()
                .map_err(|_| map_input("secret_key must be 32 bytes"))?;
            SecretKey::from_bytes(&arr).map_err(map_err)?
        }
        None => SecretKey::generate(),
    };
    let endpoint = rt().block_on(
        Endpoint::builder(N0)
            .secret_key(sk)
            .bind(),
    ).map_err(map_err)?;
    Ok(Arc::new(FarmEndpoint { endpoint }))
}

pub fn endpoint_id_to_hex(endpoint_id: Vec<u8>) -> Result<String, FarmError> {
    if endpoint_id.len() != 32 {
        return Err(map_input("endpoint_id must be 32 bytes"));
    }
    Ok(hex::encode(&endpoint_id))
}

pub fn endpoint_id_from_hex(hex_str: String) -> Result<Vec<u8>, FarmError> {
    hex::decode(hex_str.trim()).map_err(|_| map_input("bad hex"))
}

impl FarmEndpoint {
    pub fn endpoint_id(&self) -> Vec<u8> {
        let id: EndpointId = self.endpoint.id();
        id.as_bytes().to_vec()
    }

    pub fn secret_key_bytes(&self) -> Vec<u8> {
        self.endpoint.secret_key().to_bytes().to_vec()
    }

    pub fn shutdown(&self) {
        let endpoint = self.endpoint.clone();
        rt().block_on(endpoint.close());
    }
}

mod hex {
    const CHARS: &[u8; 16] = b"0123456789abcdef";

    pub fn encode(bytes: &[u8]) -> String {
        let mut s = String::with_capacity(bytes.len() * 2);
        for b in bytes {
            s.push(CHARS[(b >> 4) as usize] as char);
            s.push(CHARS[(b & 0xF) as usize] as char);
        }
        s
    }

    pub fn decode(s: &str) -> Result<Vec<u8>, ()> {
        let s = s.trim();
        if s.len() % 2 != 0 {
            return Err(());
        }
        let digit = |c: u8| match c {
            b'0'..=b'9' => Ok(c - b'0'),
            b'a'..=b'f' => Ok(c - b'a' + 10),
            b'A'..=b'F' => Ok(c - b'A' + 10),
            _ => Err(()),
        };
        let bytes = s.as_bytes();
        let mut out = Vec::with_capacity(bytes.len() / 2);
        let mut i = 0;
        while i < bytes.len() {
            out.push((digit(bytes[i])? << 4) | digit(bytes[i + 1])?);
            i += 2;
        }
        Ok(out)
    }
}

uniffi::include_scaffolding!("farm_iroh");
