use std::collections::{HashMap, HashSet};
use std::sync::{
    atomic::{AtomicBool, Ordering},
    Arc, Mutex, OnceLock, RwLock,
};

use bytes::Bytes;
use futures_util::{stream, StreamExt};
use iroh::endpoint::presets::N0;
use iroh::{Endpoint, SecretKey};
use iroh_blobs::store::fs::FsStore;
use iroh_blobs::ticket::BlobTicket;
use iroh_blobs::{BlobFormat, Hash};
use thiserror::Error;
use tokio::time::Duration;

const FETCH_TIMEOUT: Duration = Duration::from_secs(120);
const MAX_FILES: usize = 100;
const MAX_FILE_BYTES: usize = 200 * 1024 * 1024;
const MAX_SYNC_MODELS: usize = 100;
const MAX_ANNOUNCE_MODELS: usize = 200;

#[derive(Debug, Error)]
pub enum FarmError {
    #[error("internal error: {0}")]
    Internal(String),
    #[error("invalid input: {0}")]
    InvalidInput(String),
    #[error("not found: {0}")]
    NotFound(String),
    #[error("timeout")]
    Timeout,
}

fn map_err<E: std::fmt::Display>(e: E) -> FarmError {
    FarmError::Internal(e.to_string())
}

fn map_input(msg: &str) -> FarmError {
    FarmError::InvalidInput(msg.to_string())
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

fn hex_encode(bytes: &[u8]) -> String {
    const CHARS: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        s.push(CHARS[(b >> 4) as usize] as char);
        s.push(CHARS[(b & 0xF) as usize] as char);
    }
    s
}

fn hex_decode(s: &str) -> Result<Vec<u8>, FarmError> {
    let s = s.trim();
    if s.len() % 2 != 0 {
        return Err(map_input("bad hex length"));
    }
    let digit = |c: u8| match c {
        b'0'..=b'9' => Ok(c - b'0'),
        b'a'..=b'f' => Ok(c - b'a' + 10),
        b'A'..=b'F' => Ok(c - b'A' + 10),
        _ => Err(map_input("bad hex char")),
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

fn parse_hash(s: &str) -> Result<Hash, FarmError> {
    let v = hex_decode(s)?;
    if v.len() != 32 {
        return Err(map_input("hash must be 32 bytes"));
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(&v);
    Ok(Hash::from_bytes(arr))
}

fn bytes_to_hash(v: &[u8]) -> Result<Hash, FarmError> {
    if v.len() != 32 {
        return Err(map_input("hash must be 32 bytes"));
    }
    let mut arr = [0u8; 32];
    arr.copy_from_slice(v);
    Ok(Hash::from_bytes(arr))
}

#[derive(Debug, Default)]
struct SearchIndex {
    kw: HashMap<String, HashSet<Hash>>,
    meta: HashMap<Hash, String>,
}

impl SearchIndex {
    fn add(&mut self, hash: Hash, metadata_json: &str) {
        if let Ok(v) = serde_json::from_str::<serde_json::Value>(metadata_json) {
            for kw in extract_keywords(&v) {
                self.kw.entry(kw).or_default().insert(hash);
            }
        }
        self.meta.insert(hash, metadata_json.to_string());
    }

    fn query(&self, keyword: &str) -> Vec<String> {
        self.kw
            .get(&keyword.to_lowercase())
            .map(|s| s.iter().map(|h| hex_encode(h.as_bytes())).collect())
            .unwrap_or_default()
    }

    fn get(&self, hash: &Hash) -> Option<String> {
        self.meta.get(hash).cloned()
    }

    fn contains(&self, hash: &Hash) -> bool {
        self.meta.contains_key(hash)
    }

    fn recent(&self, limit: usize) -> Vec<Hash> {
        self.meta.keys().take(limit).cloned().collect()
    }
}

fn words(s: &str, min: usize) -> Vec<String> {
    s.split(|c: char| !c.is_alphanumeric())
        .filter(|w| w.len() > min)
        .map(|w| w.to_lowercase())
        .collect()
}

fn extract_keywords(meta: &serde_json::Value) -> Vec<String> {
    let mut kws = Vec::new();
    if let Some(t) = meta.get("title").and_then(|v| v.as_str()) {
        kws.push(t.to_lowercase());
        kws.extend(words(t, 2));
    }
    if let Some(d) = meta.get("description").and_then(|v| v.as_str()) {
        kws.extend(words(d, 3));
    }
    if let Some(c) = meta.get("category").and_then(|v| v.as_str()) {
        kws.push(c.to_lowercase());
    }
    if let Some(tags) = meta.get("tags").and_then(|v| v.as_array()) {
        for t in tags {
            if let Some(s) = t.as_str() {
                kws.push(s.to_lowercase());
            }
        }
    }
    if let Some(name) = meta
        .get("designer")
        .and_then(|d| d.get("name"))
        .and_then(|v| v.as_str())
    {
        kws.push(name.to_lowercase());
    }
    kws.sort();
    kws.dedup();
    kws
}

#[derive(Debug, Clone)]
enum FetchPhase {
    Metadata {
        model_json: String,
        names: Vec<String>,
        hashes: Vec<String>,
    },
    Progress {
        downloaded: i64,
        total: i64,
    },
    Done {
        dir: String,
    },
    Error {
        msg: String,
    },
}

impl FetchPhase {
    fn to_json(&self) -> String {
        match self {
            FetchPhase::Metadata {
                model_json,
                names,
                hashes,
            } => serde_json::json!({
                "state": "metadata",
                "model_json": model_json,
                "names": names,
                "hashes": hashes,
            })
            .to_string(),
            FetchPhase::Progress { downloaded, total } => serde_json::json!({
                "state": "progress",
                "downloaded": downloaded,
                "total": total,
            })
            .to_string(),
            FetchPhase::Done { dir } => serde_json::json!({
                "state": "done",
                "dir": dir,
            })
            .to_string(),
            FetchPhase::Error { msg } => serde_json::json!({
                "state": "error",
                "msg": msg,
            })
            .to_string(),
        }
    }
}

struct FetchState {
    phase: Mutex<FetchPhase>,
    cancel: AtomicBool,
}

#[derive(Clone)]
struct FetchCtx {
    endpoint: Endpoint,
    store: FsStore,
    peers: Arc<RwLock<HashSet<String>>>,
    index: Arc<RwLock<SearchIndex>>,
}

async fn ctx_download(ctx: &FetchCtx, ticket: &BlobTicket) -> Result<Hash, FarmError> {
    let hash = ticket.hash();
    let peer = ticket.addr().id;
    let downloader = ctx.store.downloader(&ctx.endpoint);
    let req = downloader.download(hash, Some(peer));
    let mut stream = tokio::time::timeout(FETCH_TIMEOUT, req.stream())
        .await
        .map_err(|_| FarmError::Timeout)?
        .map_err(map_err)?;
    tokio::time::timeout(FETCH_TIMEOUT, async {
        loop {
            match stream.next().await {
                Some(iroh_blobs::api::downloader::DownloadProgressItem::PartComplete {
                    ..
                }) => break,
                Some(iroh_blobs::api::downloader::DownloadProgressItem::DownloadError)
                | Some(
                    iroh_blobs::api::downloader::DownloadProgressItem::ProviderFailed { .. },
                ) => {
                    return Err::<(), FarmError>(map_input("download failed"));
                }
                Some(iroh_blobs::api::downloader::DownloadProgressItem::Error(e)) => {
                    return Err(map_err(e));
                }
                Some(_) => continue,
                None => break,
            }
        }
        Ok(())
    })
    .await
    .map_err(|_| FarmError::Timeout)??;
    ctx.peers
        .write()
        .map_err(|_| map_err("lock"))?
        .insert(hex_encode(peer.as_bytes()));
    Ok(hash)
}

async fn ctx_read(ctx: &FetchCtx, hash: Hash) -> Result<Vec<u8>, FarmError> {
    let bytes = tokio::time::timeout(FETCH_TIMEOUT, ctx.store.blobs().get_bytes(hash))
        .await
        .map_err(|_| FarmError::Timeout)?
        .map_err(|_| {
            FarmError::NotFound(format!("blob {} missing", hex_encode(hash.as_bytes())))
        })?;
    if bytes.len() > MAX_FILE_BYTES + 1024 * 1024 {
        return Err(map_input("blob too large"));
    }
    Ok(bytes.to_vec())
}

fn ctx_index_add(ctx: &FetchCtx, hash: Hash, metadata_json: &str) {
    if let Ok(mut idx) = ctx.index.write() {
        idx.add(hash, metadata_json);
    }
}

async fn run_fetch(ctx: FetchCtx, ticket: BlobTicket, dir: String, state: Arc<FetchState>) {
    let set = |p: FetchPhase| {
        if let Ok(mut g) = state.phase.lock() {
            *g = p;
        }
    };
    let cancelled = || state.cancel.load(Ordering::Relaxed);
    let meta_hash = match ctx_download(&ctx, &ticket).await {
        Ok(h) => h,
        Err(e) => {
            set(FetchPhase::Error { msg: e.to_string() });
            return;
        }
    };
    if cancelled() {
        set(FetchPhase::Error {
            msg: "cancelled".into(),
        });
        return;
    }
    let meta_bytes = match ctx_read(&ctx, meta_hash).await {
        Ok(b) => b,
        Err(e) => {
            set(FetchPhase::Error { msg: e.to_string() });
            return;
        }
    };
    let meta_json = match String::from_utf8(meta_bytes) {
        Ok(s) => s,
        Err(_) => {
            set(FetchPhase::Error {
                msg: "metadata not utf-8".into(),
            });
            return;
        }
    };
    ctx_index_add(&ctx, meta_hash, &meta_json);
    let v: serde_json::Value = match serde_json::from_str(&meta_json) {
        Ok(v) => v,
        Err(_) => {
            set(FetchPhase::Error {
                msg: "bad metadata json".into(),
            });
            return;
        }
    };
    let str_list = |key: &str| -> Vec<String> {
        v.get(key)
            .and_then(|f| f.as_array())
            .map(|a| {
                a.iter()
                    .filter_map(|x| x.as_str().map(|s| s.to_string()))
                    .collect()
            })
            .unwrap_or_default()
    };
    let names = str_list("files");
    let hashes = str_list("hashes");
    let tickets = str_list("tickets");
    let total: i64 = v
        .get("sizes")
        .and_then(|f| f.as_array())
        .map(|a| a.iter().filter_map(|x| x.as_i64()).sum())
        .unwrap_or(-1);
    if names.is_empty() || names.len() != tickets.len() {
        set(FetchPhase::Error {
            msg: "metadata missing file tickets".into(),
        });
        return;
    }
    set(FetchPhase::Metadata {
        model_json: meta_json.clone(),
        names: names.clone(),
        hashes: hashes.clone(),
    });
    let dir_path = std::path::PathBuf::from(&dir);
    if let Err(e) = std::fs::create_dir_all(&dir_path) {
        set(FetchPhase::Error {
            msg: format!("mkdir failed: {e}"),
        });
        return;
    }
    let mut downloaded: i64 = 0;
    for (i, name) in names.iter().enumerate() {
        if cancelled() {
            set(FetchPhase::Error {
                msg: "cancelled".into(),
            });
            return;
        }
        if name.contains('/') || name.contains('\\') || name.contains("..") {
            set(FetchPhase::Error {
                msg: format!("unsafe name: {name}"),
            });
            return;
        }
        let ft: BlobTicket = match tickets[i].trim().parse() {
            Ok(x) => x,
            Err(_) => {
                set(FetchPhase::Error {
                    msg: "bad file ticket".into(),
                });
                return;
            }
        };
        let fh = match ctx_download(&ctx, &ft).await {
            Ok(h) => h,
            Err(e) => {
                set(FetchPhase::Error { msg: e.to_string() });
                return;
            }
        };
        let data = match ctx_read(&ctx, fh).await {
            Ok(d) => d,
            Err(e) => {
                set(FetchPhase::Error { msg: e.to_string() });
                return;
            }
        };
        downloaded += data.len() as i64;
        if std::fs::write(dir_path.join(name), &data).is_err() {
            set(FetchPhase::Error {
                msg: "write failed".into(),
            });
            return;
        }
        set(FetchPhase::Progress { downloaded, total });
    }
    set(FetchPhase::Done { dir });
}

pub struct FarmEndpoint {
    endpoint: Endpoint,
    store: FsStore,
    peers: Arc<RwLock<HashSet<String>>>,
    index: Arc<RwLock<SearchIndex>>,
    tags: Arc<RwLock<HashMap<Hash, String>>>,
    fetches: Mutex<HashMap<i64, Arc<FetchState>>>,
    next_fetch: Mutex<i64>,
}

pub fn connect(secret_key: Vec<u8>, data_dir: String) -> Result<Arc<FarmEndpoint>, FarmError> {
    let sk = if secret_key.is_empty() {
        SecretKey::generate()
    } else {
        let arr: [u8; 32] = secret_key
            .try_into()
            .map_err(|_| map_input("secret_key must be 32 bytes"))?;
        SecretKey::from_bytes(&arr)
    };
    std::fs::create_dir_all(&data_dir).map_err(map_err)?;
    let (endpoint, store) = rt().block_on(async {
        let endpoint = Endpoint::builder(N0)
            .secret_key(sk)
            .bind()
            .await
            .map_err(map_err)?;
        let store = FsStore::load(data_dir).await.map_err(map_err)?;
        Ok::<_, FarmError>((endpoint, store))
    })?;
    Ok(Arc::new(FarmEndpoint {
        endpoint,
        store,
        peers: Arc::new(RwLock::new(HashSet::new())),
        index: Arc::new(RwLock::new(SearchIndex::default())),
        tags: Arc::new(RwLock::new(HashMap::new())),
        fetches: Mutex::new(HashMap::new()),
        next_fetch: Mutex::new(1),
    }))
}

pub fn endpoint_id_to_hex(endpoint_id: Vec<u8>) -> Result<String, FarmError> {
    if endpoint_id.len() != 32 {
        return Err(map_input("endpoint_id must be 32 bytes"));
    }
    Ok(hex_encode(&endpoint_id))
}

pub fn endpoint_id_from_hex(hex_str: String) -> Result<Vec<u8>, FarmError> {
    hex_decode(&hex_str)
}

impl FarmEndpoint {
    pub fn endpoint_id(&self) -> Vec<u8> {
        self.endpoint.id().as_bytes().to_vec()
    }

    pub fn secret_key_bytes(&self) -> Vec<u8> {
        self.endpoint.secret_key().to_bytes().to_vec()
    }

    pub fn shutdown(&self) {
        let endpoint = self.endpoint.clone();
        rt().block_on(endpoint.close());
    }

    fn ticket_for_hash(&self, hash: Hash) -> String {
        BlobTicket::new(self.endpoint.addr(), hash, BlobFormat::Raw).to_string()
    }

    async fn add_bytes(&self, data: Vec<u8>) -> Result<Hash, FarmError> {
        if data.len() > MAX_FILE_BYTES {
            return Err(map_input("blob too large"));
        }
        let s = stream::once(async move { Ok::<_, std::io::Error>(Bytes::from(data)) });
        let info = self
            .store
            .blobs()
            .add_stream(s)
            .await
            .with_tag()
            .await
            .map_err(map_err)?;
        self.tags
            .write()
            .map_err(|_| map_err("lock"))?
            .insert(info.hash, info.name.to_string());
        Ok(info.hash)
    }

    fn ctx(&self) -> FetchCtx {
        FetchCtx {
            endpoint: self.endpoint.clone(),
            store: self.store.clone(),
            peers: Arc::clone(&self.peers),
            index: Arc::clone(&self.index),
        }
    }

    async fn download_blob(&self, ticket: &BlobTicket) -> Result<Hash, FarmError> {
        ctx_download(&self.ctx(), ticket).await
    }

    async fn read_blob(&self, hash: Hash) -> Result<Vec<u8>, FarmError> {
        ctx_read(&self.ctx(), hash).await
    }

    fn index_add(&self, hash: Hash, metadata_json: &str) -> Result<(), FarmError> {
        ctx_index_add(&self.ctx(), hash, metadata_json);
        Ok(())
    }

    pub fn blob_add(&self, data: Vec<u8>) -> Result<Vec<u8>, FarmError> {
        let hash = rt().block_on(self.add_bytes(data))?;
        Ok(hash.as_bytes().to_vec())
    }

    pub fn blob_get(&self, hash: Vec<u8>) -> Result<Vec<u8>, FarmError> {
        let h = bytes_to_hash(&hash)?;
        rt().block_on(self.read_blob(h))
    }

    pub fn blob_has(&self, hash: Vec<u8>) -> Result<bool, FarmError> {
        let h = bytes_to_hash(&hash)?;
        rt().block_on(async { self.store.blobs().has(h).await.map_err(map_err) })
    }

    pub fn blob_remove(&self, hash: Vec<u8>) -> Result<(), FarmError> {
        let h = bytes_to_hash(&hash)?;
        let name = self
            .tags
            .write()
            .map_err(|_| map_err("lock"))?
            .remove(&h);
        if let Some(name) = name {
            rt().block_on(async {
                self.store.tags().delete(name).await.map_err(map_err)?;
                Ok::<_, FarmError>(())
            })
        } else {
            Ok(())
        }
    }

    pub fn ticket_for(&self, hash: Vec<u8>) -> Result<String, FarmError> {
        let h = bytes_to_hash(&hash)?;
        let has: bool =
            rt().block_on(async { self.store.blobs().has(h).await.map_err(map_err) })?;
        if !has {
            return Err(FarmError::NotFound("blob not stored locally".into()));
        }
        Ok(self.ticket_for_hash(h))
    }

    pub fn ticket_info(&self, ticket: String) -> Result<String, FarmError> {
        let t: BlobTicket = ticket.trim().parse().map_err(|_| map_input("bad ticket"))?;
        Ok(format!(
            "{}:{}",
            hex_encode(t.hash().as_bytes()),
            hex_encode(t.addr().id.as_bytes())
        ))
    }

    pub fn model_publish(
        &self,
        model_json: String,
        files: Vec<Vec<u8>>,
    ) -> Result<String, FarmError> {
        if files.is_empty() || files.len() > MAX_FILES {
            return Err(map_input("bad file count"));
        }
        let mut v: serde_json::Value =
            serde_json::from_str(&model_json).map_err(|_| map_input("bad model json"))?;
        let names = v
            .get("files")
            .and_then(|f| f.as_array())
            .ok_or_else(|| map_input("metadata missing files"))?;
        if names.len() != files.len() {
            return Err(map_input("file count mismatch"));
        }
        let mut hashes = Vec::with_capacity(files.len());
        let mut tickets = Vec::with_capacity(files.len());
        let mut sizes = Vec::with_capacity(files.len());
        for data in files {
            sizes.push(data.len() as i64);
            let h = rt().block_on(self.add_bytes(data))?;
            tickets.push(self.ticket_for_hash(h));
            hashes.push(hex_encode(h.as_bytes()));
        }
        let obj = v
            .as_object_mut()
            .ok_or_else(|| map_input("metadata must be object"))?;
        obj.insert(
            "hashes".into(),
            serde_json::Value::Array(
                hashes.into_iter().map(serde_json::Value::String).collect(),
            ),
        );
        obj.insert(
            "tickets".into(),
            serde_json::Value::Array(
                tickets
                    .into_iter()
                    .map(serde_json::Value::String)
                    .collect(),
            ),
        );
        obj.insert(
            "sizes".into(),
            serde_json::Value::Array(sizes.into_iter().map(|s| serde_json::json!(s)).collect()),
        );
        let final_json = serde_json::to_string(&v).map_err(map_err)?;
        let model_hash = rt().block_on(self.add_bytes(final_json.clone().into_bytes()))?;
        self.index_add(model_hash, &final_json)?;
        Ok(self.ticket_for_hash(model_hash))
    }

    pub fn fetch_start(&self, ticket: String, dir: String) -> Result<i64, FarmError> {
        let t: BlobTicket = ticket.trim().parse().map_err(|_| map_input("bad ticket"))?;
        let handle = {
            let mut n = self.next_fetch.lock().map_err(|_| map_err("lock"))?;
            let h = *n;
            *n += 1;
            h
        };
        let state = Arc::new(FetchState {
            phase: Mutex::new(FetchPhase::Progress {
                downloaded: 0,
                total: -1,
            }),
            cancel: AtomicBool::new(false),
        });
        self.fetches
            .lock()
            .map_err(|_| map_err("lock"))?
            .insert(handle, state.clone());
        let ctx = FetchCtx {
            endpoint: self.endpoint.clone(),
            store: self.store.clone(),
            peers: Arc::clone(&self.peers),
            index: Arc::clone(&self.index),
        };
        rt().spawn(async move {
            run_fetch(ctx, t, dir, state).await;
        });
        Ok(handle)
    }

    pub fn fetch_poll(&self, handle: i64) -> Result<String, FarmError> {
        let map = self.fetches.lock().map_err(|_| map_err("lock"))?;
        let st = map
            .get(&handle)
            .ok_or_else(|| FarmError::NotFound("unknown fetch".into()))?;
        let phase = st.phase.lock().map_err(|_| map_err("lock"))?.clone();
        Ok(phase.to_json())
    }

    pub fn fetch_stop(&self, handle: i64) {
        if let Ok(map) = self.fetches.lock() {
            if let Some(st) = map.get(&handle) {
                st.cancel.store(true, Ordering::Relaxed);
            }
        }
    }

    pub fn search_query(&self, keyword: String) -> Result<Vec<String>, FarmError> {
        let kw = keyword.trim().to_lowercase();
        if kw.is_empty() {
            return Err(map_input("empty keyword"));
        }
        Ok(self
            .index
            .read()
            .map_err(|_| map_err("lock"))?
            .query(&kw))
    }

    pub fn search_get_metadata(&self, model_hash: String) -> Result<String, FarmError> {
        let h = parse_hash(&model_hash)?;
        self.index
            .read()
            .map_err(|_| map_err("lock"))?
            .get(&h)
            .ok_or_else(|| FarmError::NotFound("model not indexed".into()))
    }

    pub fn blob_fetch(&self, ticket: String) -> Result<Vec<u8>, FarmError> {
        let t: BlobTicket = ticket.trim().parse().map_err(|_| map_input("bad ticket"))?;
        let h = rt().block_on(self.download_blob(&t))?;
        rt().block_on(self.read_blob(h))
    }

    pub fn sync_announce(&self, profile_ticket: String) -> Result<String, FarmError> {
        let entries = self
            .index
            .read()
            .map_err(|_| map_err("lock"))?
            .recent(MAX_ANNOUNCE_MODELS);
        let mut models = Vec::with_capacity(entries.len());
        for h in &entries {
            models.push(serde_json::json!({
                "h": hex_encode(h.as_bytes()),
                "t": self.ticket_for_hash(*h),
            }));
        }
        let mut ann = serde_json::json!({"v": 1, "models": models});
        let pt = profile_ticket.trim().to_string();
        if !pt.is_empty() {
            if pt.parse::<BlobTicket>().is_err() {
                return Err(map_input("bad profile ticket"));
            }
            ann["profile"] = serde_json::Value::String(pt);
        }
        let ann = ann.to_string();
        let h = rt().block_on(self.add_bytes(ann.into_bytes()))?;
        Ok(self.ticket_for_hash(h))
    }

    pub fn sync_merge(&self, ticket: String) -> Result<String, FarmError> {
        let t: BlobTicket = ticket.trim().parse().map_err(|_| map_input("bad ticket"))?;
        let ah = rt().block_on(self.download_blob(&t))?;
        let ab = rt().block_on(self.read_blob(ah))?;
        let ann: serde_json::Value =
            serde_json::from_slice(&ab).map_err(|_| map_input("bad announcement"))?;
        let models = ann
            .get("models")
            .and_then(|m| m.as_array())
            .ok_or_else(|| map_input("bad announcement"))?;
        let mut tickets = Vec::new();
        let mut profiles = Vec::new();
        if let Some(pt) = ann.get("profile").and_then(|x| x.as_str()) {
            if !pt.trim().is_empty() {
                profiles.push(pt.to_string());
            }
        }
        for m in models.iter().take(MAX_SYNC_MODELS) {
            let (h, mt) = match (
                m.get("h").and_then(|x| x.as_str()),
                m.get("t").and_then(|x| x.as_str()),
            ) {
                (Some(a), Some(b)) => (a, b),
                _ => continue,
            };
            let hash = match parse_hash(h) {
                Ok(x) => x,
                Err(_) => continue,
            };
            let known = self
                .index
                .read()
                .map_err(|_| map_err("lock"))?
                .contains(&hash);
            if known {
                continue;
            }
            let mkt: BlobTicket = match mt.trim().parse() {
                Ok(x) => x,
                Err(_) => continue,
            };
            let mh = match rt().block_on(self.download_blob(&mkt)) {
                Ok(x) => x,
                Err(_) => continue,
            };
            let mb = match rt().block_on(self.read_blob(mh)) {
                Ok(x) => x,
                Err(_) => continue,
            };
            let ms = match String::from_utf8(mb) {
                Ok(x) => x,
                Err(_) => continue,
            };
            if serde_json::from_str::<serde_json::Value>(&ms).is_err() {
                continue;
            }
            let _ = self.index_add(hash, &ms);
            tickets.push(mt.to_string());
        }
        Ok(serde_json::json!({
            "new_models": tickets.len(),
            "model_tickets": tickets,
            "profile_tickets": profiles,
        })
        .to_string())
    }

    pub fn known_peers(&self) -> Vec<String> {
        let mut v: Vec<String> = self
            .peers
            .read()
            .map(|s| s.iter().cloned().collect())
            .unwrap_or_default();
        v.sort();
        v
    }
}

uniffi::include_scaffolding!("farm_iroh");
