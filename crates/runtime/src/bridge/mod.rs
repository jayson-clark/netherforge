//! The dev bridge's server end (Kotlin's `bridge/`): the connection to the editor that started this dev server
//! (`client`), its frames (`frames`), and what answers the editor's requests on the main thread (`service`).

pub mod client;
pub mod frames;
