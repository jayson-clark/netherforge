//! `netherforge-bundle.json` (format's `BundleManifest`): what `netherforge build` writes at the top of a bundle, a
//! project and every package it depends on, each in a folder named after its namespace, with what each must hash to.
//! A production server runs a bundle as it is: it never resolves a dependency itself.

use serde::{Deserialize, Serialize};

use crate::canonical::Map;

pub const FILE_NAME: &str = "netherforge-bundle.json";

/// The resource pack built into a bundle, beside its manifest.
pub const RESOURCE_PACK_FILE: &str = "resource-pack.zip";

/// What a signing key is written as: this, and its 32 bytes in base64.
pub const KEY_PREFIX: &str = "ed25519:";

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct BundleManifest {
    /// The project file format the bundle's packages are in.
    pub format_version: i64,
    /// The namespace of the project the bundle runs; every other package is one it depends on.
    pub project: String,
    /// Every package in the bundle, the project's own included, by namespace.
    pub packages: Map<BundledPackage>,
    /// Who vouches for it: each an Ed25519 signature over [`BundleManifest::signed_text`].
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub signatures: Option<Vec<BundleSignature>>,
    /// The project's resource pack, built with the bundle: sent as it is to a server of its pack format.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resource_pack: Option<BundledResourcePack>,
    /// The packages the bundle leaves out for the server to provide (`packages.provided`).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub provided: Option<Vec<String>>,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BundledPackage {
    pub version: String,
    /// The package hash its folder must have ([`crate::package::hash`]).
    pub hash: String,
}

/// One signature: the signing `key` (`ed25519:<base64 of its 32 bytes>`) and the `signature` in base64.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BundleSignature {
    pub key: String,
    pub signature: String,
}

/// A resource pack built into a bundle: its SHA-1, and the pack format it's for.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct BundledResourcePack {
    pub sha1: String,
    pub format: Vec<i64>,
}

impl BundleManifest {
    /// `text` as a manifest, or why it isn't one.
    pub fn parse(text: &str) -> Result<BundleManifest, Box<crate::Problem>> {
        crate::read::parse(text, FILE_NAME)
    }

    /// The manifest in its canonical form: `provided` sorted, without repeats.
    pub fn canonical(&self) -> BundleManifest {
        BundleManifest {
            provided: self.provided.as_deref().map(crate::canonical::set),
            ..self.clone()
        }
    }

    /// Its file, as format writes it (a bundle names no schema: it's for a server, not an editor).
    pub fn write(&self) -> String {
        crate::canonical::write(&self.canonical())
    }

    /// What a signature signs: the manifest as it's written without its signatures, so a countersignature adds one.
    pub fn signed_text(&self) -> String {
        BundleManifest {
            signatures: None,
            ..self.clone()
        }
        .write()
    }
}
