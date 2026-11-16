//! What an instance sees as its file system (Kotlin's `WasmData` and `WasmFiles`): its module's data at its root,
//! read-only, and nothing else. A name is looked up in the module's data or doesn't exist, so no host path a module
//! writes ever reaches the disk: writing, making, renaming or removing anything is `EROFS`, and a path that climbs
//! out of the root is `ENOTCAPABLE`. The root is descriptor 3, the first after the standard streams, as a WASI
//! program expects its first preopen.

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::io::{Read as _, Seek as _, SeekFrom};
use std::path::PathBuf;
use std::sync::Arc;

use super::wasi::{Errno, Mem};

/// Where one data file's bytes are: on disk (read as the module reads, never loaded whole), or held (a source with
/// no disk under it: tests, a package read from an archive).
#[derive(Clone, Debug)]
pub enum DataFile {
    Disk(PathBuf),
    Bytes(Arc<[u8]>),
}

/// A module's data: the files beside `module.wasm` in its folder, by their path there (`nets/big.nnue`). The same
/// for every instance of the module, so it's worked out once, when it's compiled.
#[derive(Clone, Debug, Default)]
pub struct WasmData {
    files: BTreeMap<String, DataFile>,
    folders: BTreeSet<String>,
}

impl WasmData {
    pub fn new(files: BTreeMap<String, DataFile>) -> WasmData {
        let mut folders = BTreeSet::from([String::new()]);
        for file in files.keys() {
            let mut at = file.as_str();
            while let Some((folder, _)) = at.rsplit_once('/') {
                folders.insert(folder.to_owned());
                at = folder;
            }
        }
        WasmData { files, folders }
    }

    pub fn file(&self, path: &str) -> Option<&DataFile> {
        self.files.get(path)
    }

    pub fn is_folder(&self, path: &str) -> bool {
        self.folders.contains(path)
    }

    /// What's directly in `folder`, sorted: each name, and whether it's a folder.
    pub fn list(&self, folder: &str) -> Vec<(String, bool)> {
        let prefix = if folder.is_empty() {
            String::new()
        } else {
            format!("{folder}/")
        };
        let mut names = BTreeMap::new();
        for (path, is_folder) in self
            .files
            .keys()
            .map(|p| (p, false))
            .chain(self.folders.iter().map(|p| (p, true)))
        {
            let Some(rest) = path.strip_prefix(&prefix) else {
                continue;
            };
            if path.is_empty() || rest.is_empty() || rest.contains('/') {
                continue;
            }
            names.insert(rest.to_owned(), is_folder);
        }
        names.into_iter().collect()
    }
}

/// The root's descriptor.
pub const ROOT: i32 = 3;
const ROOT_NAME: &[u8] = b"/";
/// Descriptors one instance may have open, its root included.
const MAX_OPEN: usize = 64;
const CHUNK: usize = 65_536;
const DIRENT: usize = 24;

const PREOPEN_DIRECTORY: u8 = 0;
pub const DIRECTORY: u8 = 3;
pub const REGULAR_FILE: u8 = 4;

const WHENCE_SET: i32 = 0;
const WHENCE_CURRENT: i32 = 1;
const WHENCE_END: i32 = 2;

const OPEN_CREATE: i32 = 1;
const OPEN_DIRECTORY: i32 = 2;
const OPEN_EXCLUSIVE: i32 = 4;
const OPEN_TRUNCATE: i32 = 8;
const FD_APPEND: i32 = 1;

// WASI preview 1's rights, by bit.
const FD_DATASYNC: u64 = 1 << 0;
const FD_READ: u64 = 1 << 1;
const FD_SEEK: u64 = 1 << 2;
const FD_SYNC: u64 = 1 << 4;
const FD_TELL: u64 = 1 << 5;
const FD_WRITE: u64 = 1 << 6;
const FD_ADVISE: u64 = 1 << 7;
const FD_ALLOCATE: u64 = 1 << 8;
const PATH_OPEN: u64 = 1 << 13;
const FD_READDIR: u64 = 1 << 14;
const PATH_READLINK: u64 = 1 << 15;
const PATH_FILESTAT_GET: u64 = 1 << 18;
const FD_FILESTAT_GET: u64 = 1 << 21;
const FD_FILESTAT_SET_SIZE: u64 = 1 << 22;
const POLL_FD_READWRITE: u64 = 1 << 27;

const WRITE_RIGHTS: u64 = FD_WRITE | FD_ALLOCATE | FD_FILESTAT_SET_SIZE;
const FILE_RIGHTS: u64 = FD_DATASYNC
    | FD_READ
    | FD_SEEK
    | FD_SYNC
    | FD_TELL
    | FD_ADVISE
    | FD_FILESTAT_GET
    | POLL_FD_READWRITE;
const FOLDER_RIGHTS: u64 =
    PATH_OPEN | FD_READDIR | PATH_READLINK | PATH_FILESTAT_GET | FD_FILESTAT_GET;

struct Open {
    path: String,
    folder: bool,
    disk: Option<std::fs::File>,
    position: u64,
}

impl Open {
    fn new(path: String, folder: bool) -> Open {
        Open {
            path,
            folder,
            disk: None,
            position: 0,
        }
    }
}

/// One instance's descriptors past the standard streams, used only by the thread running it.
pub struct Files {
    data: Arc<WasmData>,
    open: HashMap<i32, Open>,
    next: i32,
}

/// A path's inode number, as Kotlin's was (its Java hash): only told apart, never looked up.
fn inode(path: &str) -> u64 {
    let mut hash: i32 = 0;
    for unit in path.encode_utf16() {
        hash = hash.wrapping_mul(31).wrapping_add(i32::from(unit));
    }
    u64::from(hash as u32)
}

impl Files {
    pub fn new(data: Arc<WasmData>) -> Files {
        Files {
            data,
            open: HashMap::from([(ROOT, Open::new(String::new(), true))]),
            next: ROOT + 1,
        }
    }

    /// Whether `fd` is one of these.
    pub fn has(&self, fd: i32) -> bool {
        self.open.contains_key(&fd)
    }

    pub fn prestat_get(&self, mem: &mut Mem, fd: i32, at: u32) -> Errno {
        if fd != ROOT {
            return Errno::BADF;
        }
        mem.u8(at, PREOPEN_DIRECTORY)
            .and_then(|()| mem.u32(at + 4, ROOT_NAME.len() as u32))
            .map_or(Errno::FAULT, |()| Errno::SUCCESS)
    }

    pub fn prestat_dir_name(&self, mem: &mut Mem, fd: i32, at: u32, len: u32) -> Errno {
        if fd != ROOT {
            return Errno::BADF;
        }
        if (len as usize) < ROOT_NAME.len() {
            return Errno::NAMETOOLONG;
        }
        mem.write(at, ROOT_NAME)
            .map_or(Errno::FAULT, |()| Errno::SUCCESS)
    }

    /// `path` from the folder open at `fd`, with `.` and `..` worked out; none when it climbs out of the root.
    fn resolve(&self, folder: &str, path: &[u8]) -> Option<String> {
        let path = String::from_utf8_lossy(path);
        let mut parts: Vec<&str> = if folder.is_empty() {
            Vec::new()
        } else {
            folder.split('/').collect()
        };
        for part in path.split('/') {
            match part {
                "" | "." => {}
                ".." => {
                    parts.pop()?;
                }
                part => parts.push(part),
            }
        }
        Some(parts.join("/"))
    }

    fn exists(&self, path: &str) -> bool {
        self.data.is_folder(path) || self.data.file(path).is_some()
    }

    /// `path_open`: only reading what's there opens.
    #[allow(clippy::too_many_arguments)]
    pub fn path_open(
        &mut self,
        mem: &mut Mem,
        fd: i32,
        path: (u32, u32),
        open_flags: i32,
        rights: u64,
        fd_flags: i32,
        out: u32,
    ) -> Errno {
        let Some(folder) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        if !folder.folder {
            return Errno::NOTDIR;
        }
        let Some(name) = mem.read(path.0, path.1) else {
            return Errno::FAULT;
        };
        let Some(path) = self.resolve(&folder.path, &name) else {
            return Errno::NOTCAPABLE;
        };
        let is_folder = self.data.is_folder(&path);
        let exists = self.exists(&path);
        if open_flags & OPEN_EXCLUSIVE != 0 && exists {
            return Errno::EXIST;
        }
        if open_flags & (OPEN_CREATE | OPEN_TRUNCATE) != 0
            || fd_flags & FD_APPEND != 0
            || rights & WRITE_RIGHTS != 0
        {
            return if exists || open_flags & OPEN_CREATE != 0 {
                Errno::ROFS
            } else {
                Errno::NOENT
            };
        }
        if !exists {
            return Errno::NOENT;
        }
        if open_flags & OPEN_DIRECTORY != 0 && !is_folder {
            return Errno::NOTDIR;
        }
        if self.open.len() >= MAX_OPEN {
            return Errno::MFILE;
        }
        let number = self.next;
        if mem.u32(out, number as u32).is_none() {
            return Errno::FAULT;
        }
        self.next += 1;
        self.open.insert(number, Open::new(path, is_folder));
        Errno::SUCCESS
    }

    fn size(&mut self, fd: i32) -> Option<u64> {
        let open = self.open.get_mut(&fd)?;
        match self.data.file(&open.path)? {
            DataFile::Bytes(bytes) => Some(bytes.len() as u64),
            DataFile::Disk(path) => {
                if open.disk.is_none() {
                    open.disk = std::fs::File::open(path).ok();
                }
                open.disk.as_ref()?.metadata().ok().map(|m| m.len())
            }
        }
    }

    /// Reads up to `into.len()` bytes of the file open at `fd` from `at`.
    fn read_at(&mut self, fd: i32, at: u64, into: &mut [u8]) -> Option<usize> {
        let open = self.open.get_mut(&fd)?;
        match self.data.file(&open.path)? {
            DataFile::Bytes(bytes) => {
                let start = usize::try_from(at).ok()?.min(bytes.len());
                let n = into.len().min(bytes.len() - start);
                into[..n].copy_from_slice(&bytes[start..start + n]);
                Some(n)
            }
            DataFile::Disk(path) => {
                if open.disk.is_none() {
                    open.disk = std::fs::File::open(path).ok();
                }
                let file = open.disk.as_mut()?;
                file.seek(SeekFrom::Start(at)).ok()?;
                file.read(into).ok()
            }
        }
    }

    /// `fd_read`, or `fd_pread` from `at` (which leaves the position where it was).
    pub fn read(
        &mut self,
        mem: &mut Mem,
        fd: i32,
        iovs: (u32, u32),
        out: u32,
        at: Option<u64>,
    ) -> Errno {
        let Some(open) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        if open.folder {
            return Errno::ISDIR;
        }
        let mut position = at.unwrap_or(open.position);
        let mut total: u32 = 0;
        let mut buffer = vec![0u8; CHUNK];
        for i in 0..iovs.1 {
            let (Some(base), Some(length)) =
                (mem.get_u32(iovs.0 + i * 8), mem.get_u32(iovs.0 + i * 8 + 4))
            else {
                return Errno::FAULT;
            };
            let mut done: u32 = 0;
            while done < length {
                let want = ((length - done) as usize).min(CHUNK);
                let Some(read) = self.read_at(fd, position, &mut buffer[..want]) else {
                    return Errno::IO;
                };
                if read == 0 {
                    break;
                }
                if mem.write(base + done, &buffer[..read]).is_none() {
                    return Errno::FAULT;
                }
                done += read as u32;
                position += read as u64;
            }
            total += done;
            if done < length {
                break;
            }
        }
        if at.is_none()
            && let Some(open) = self.open.get_mut(&fd)
        {
            open.position = position;
        }
        mem.u32(out, total)
            .map_or(Errno::FAULT, |()| Errno::SUCCESS)
    }

    pub fn seek(&mut self, mem: &mut Mem, fd: i32, offset: i64, whence: i32, out: u32) -> Errno {
        let Some(open) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        if open.folder {
            return Errno::SPIPE;
        }
        let position = open.position as i64;
        let Some(size) = self.size(fd) else {
            return Errno::IO;
        };
        let to = match whence {
            WHENCE_SET => offset,
            WHENCE_CURRENT => position.saturating_add(offset),
            WHENCE_END => (size as i64).saturating_add(offset),
            _ => return Errno::INVAL,
        };
        if to < 0 {
            return Errno::INVAL;
        }
        if mem.u64(out, to as u64).is_none() {
            return Errno::FAULT;
        }
        if let Some(open) = self.open.get_mut(&fd) {
            open.position = to as u64;
        }
        Errno::SUCCESS
    }

    pub fn tell(&self, mem: &mut Mem, fd: i32, out: u32) -> Errno {
        let Some(open) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        mem.u64(out, open.position)
            .map_or(Errno::FAULT, |()| Errno::SUCCESS)
    }

    pub fn close(&mut self, fd: i32) -> Errno {
        if fd == ROOT {
            return Errno::NOTSUP;
        }
        match self.open.remove(&fd) {
            Some(_) => Errno::SUCCESS,
            None => Errno::BADF,
        }
    }

    pub fn fdstat(&self, mem: &mut Mem, fd: i32, at: u32) -> Errno {
        let Some(open) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        let (kind, base, inheriting) = if open.folder {
            (DIRECTORY, FOLDER_RIGHTS, FOLDER_RIGHTS | FILE_RIGHTS)
        } else {
            (REGULAR_FILE, FILE_RIGHTS, 0)
        };
        fdstat(mem, at, kind, base, inheriting)
    }

    fn stat(&mut self, mem: &mut Mem, at: u32, path: &str, folder: bool) -> Errno {
        let size = if folder {
            0
        } else {
            match self.data.file(path) {
                Some(DataFile::Bytes(bytes)) => bytes.len() as u64,
                Some(DataFile::Disk(disk)) => match std::fs::metadata(disk) {
                    Ok(meta) => meta.len(),
                    Err(_) => return Errno::IO,
                },
                None => return Errno::IO,
            }
        };
        filestat(
            mem,
            at,
            if folder { DIRECTORY } else { REGULAR_FILE },
            inode(path),
            size,
        )
    }

    pub fn filestat(&mut self, mem: &mut Mem, fd: i32, at: u32) -> Errno {
        let Some(open) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        let (path, folder) = (open.path.clone(), open.folder);
        self.stat(mem, at, &path, folder)
    }

    pub fn path_filestat(&mut self, mem: &mut Mem, fd: i32, path: (u32, u32), at: u32) -> Errno {
        let Some(folder) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        let Some(name) = mem.read(path.0, path.1) else {
            return Errno::FAULT;
        };
        let Some(path) = self.resolve(&folder.path.clone(), &name) else {
            return Errno::NOTCAPABLE;
        };
        if self.data.is_folder(&path) {
            self.stat(mem, at, &path, true)
        } else if self.data.file(&path).is_some() {
            self.stat(mem, at, &path, false)
        } else {
            Errno::NOENT
        }
    }

    pub fn readlink(&self, mem: &mut Mem, fd: i32, path: (u32, u32)) -> Errno {
        let Some(folder) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        let Some(name) = mem.read(path.0, path.1) else {
            return Errno::FAULT;
        };
        match self.resolve(&folder.path, &name) {
            None => Errno::NOTCAPABLE,
            Some(path) if self.exists(&path) => Errno::INVAL,
            Some(_) => Errno::NOENT,
        }
    }

    /// `fd_readdir(fd, buf, buf_len, cookie, bufused)`: entries from the `cookie`th, as many bytes as fit.
    pub fn readdir(
        &self,
        mem: &mut Mem,
        fd: i32,
        buffer: (u32, u32),
        cookie: u64,
        out: u32,
    ) -> Errno {
        let Some(open) = self.open.get(&fd) else {
            return Errno::BADF;
        };
        if !open.folder {
            return Errno::NOTDIR;
        }
        let entries = self.data.list(&open.path);
        let (at, length) = (buffer.0 as usize, buffer.1 as usize);
        let mut used = 0usize;
        let mut index = usize::try_from(cookie).unwrap_or(usize::MAX);
        while index < entries.len() && used < length {
            let (name, folder) = &entries[index];
            let full = if open.path.is_empty() {
                name.clone()
            } else {
                format!("{}/{name}", open.path)
            };
            let mut entry = Vec::with_capacity(DIRENT + name.len());
            entry.extend_from_slice(&(index as u64 + 1).to_le_bytes());
            entry.extend_from_slice(&inode(&full).to_le_bytes());
            entry.extend_from_slice(&(name.len() as u32).to_le_bytes());
            entry.push(if *folder { DIRECTORY } else { REGULAR_FILE });
            entry.extend_from_slice(&[0; 3]);
            entry.extend_from_slice(name.as_bytes());
            let fits = entry.len().min(length - used);
            if mem.write((at + used) as u32, &entry[..fits]).is_none() {
                return Errno::FAULT;
            }
            used += fits;
            index += 1;
        }
        mem.u32(out, used as u32)
            .map_or(Errno::FAULT, |()| Errno::SUCCESS)
    }
}

/// An `fdstat` at `at`: its file type, no flags, its rights.
pub fn fdstat(mem: &mut Mem, at: u32, kind: u8, base: u64, inheriting: u64) -> Errno {
    let written = mem
        .u8(at, kind)
        .and_then(|()| mem.u16(at + 2, 0))
        .and_then(|()| mem.u64(at + 8, base))
        .and_then(|()| mem.u64(at + 16, inheriting));
    written.map_or(Errno::FAULT, |()| Errno::SUCCESS)
}

/// A `filestat` at `at`: no device, the inode, its type, one link, its size, no times.
pub fn filestat(mem: &mut Mem, at: u32, kind: u8, inode: u64, size: u64) -> Errno {
    let written = mem
        .u64(at, 0)
        .and_then(|()| mem.u64(at + 8, inode))
        .and_then(|()| mem.u8(at + 16, kind))
        .and_then(|()| mem.u64(at + 24, 1))
        .and_then(|()| mem.u64(at + 32, size))
        .and_then(|()| mem.u64(at + 40, 0))
        .and_then(|()| mem.u64(at + 48, 0))
        .and_then(|()| mem.u64(at + 56, 0));
    written.map_or(Errno::FAULT, |()| Errno::SUCCESS)
}

/// The rights the standard streams have: reading or writing, and polling.
pub const STREAM_READ_RIGHTS: u64 = FD_READ | POLL_FD_READWRITE;
pub const STREAM_WRITE_RIGHTS: u64 = FD_WRITE | POLL_FD_READWRITE;
