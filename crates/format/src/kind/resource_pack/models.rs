//! A pack's model files (`models/<path>.json`): the game's JSON block and
//! item models, as Blockbench exports them (format's `PackModels`). Format
//! doesn't model the file: it checks what the game would refuse or draw as
//! the missing model, warns about display transforms the game clamps, and
//! otherwise passes the file through.

use serde_json::{Map, Value};

use crate::game;
use crate::json::{child_path, format_number};
use crate::problem::{ProblemCode, codes};

pub const FOLDER: &str = "models";
pub const EXTENSION: &str = ".json";
/// The bounds the game holds an element's corners to, on each axis, in sixteenths of a block.
pub const MIN_COORDINATE: f64 = -16.0;
pub const MAX_COORDINATE: f64 = 32.0;
/// What the game clamps a display transform's translation to, either way.
pub const MAX_TRANSLATION: f64 = 80.0;
/// What the game clamps a display transform's scale to, either way.
pub const MAX_SCALE: f64 = 4.0;
/// The faces of an element, as the game names them.
pub const FACES: [&str; 6] = ["down", "up", "north", "south", "west", "east"];
const AXES: [&str; 3] = ["x", "y", "z"];
/// How deep `#a` → `#b` → … may go before it's a loop.
const MAX_TEXTURE_CHAIN: usize = 16;
const ROTATION_RULE: &str = "A rotation is { \"origin\": [x, y, z], \"axis\": \"x\", \"y\" or \"z\", \"angle\": degrees } or { \"origin\", \"x\", \"y\", \"z\" } in degrees";

/// A model's path under `models/`: a relative path to a `.json` inside the folder.
pub const MODEL_PATH_PATTERN: &str =
    r"^([A-Za-z0-9_][A-Za-z0-9_.-]*/)*[A-Za-z0-9_][A-Za-z0-9_.-]*\.json$";
pub const MODEL_PATH_RULE: &str =
    "a .json path of letters, digits, _, - and . inside this resource pack's models/ folder";

crate::ruled_string!(
    /// A model file's path under a pack's `models/`.
    ModelPath,
    MODEL_PATH_PATTERN,
    "resource_pack.model-file",
    MODEL_PATH_RULE,
    "x-ref" = "model"
);

pub fn is_model_path(path: &str) -> bool {
    crate::rules::matches_pattern(MODEL_PATH_PATTERN, path)
}

/// One thing wrong with a model file: where in it, and what.
#[derive(Clone, Debug, PartialEq)]
pub struct Mistake {
    pub code: ProblemCode,
    pub path: String,
    pub message: String,
}

struct Mistakes(Vec<Mistake>);

impl Mistakes {
    fn add(&mut self, path: impl Into<String>, message: impl Into<String>) {
        self.with(codes::RESOURCE_PACK_MODEL, path, message);
    }

    fn with(&mut self, code: ProblemCode, path: impl Into<String>, message: impl Into<String>) {
        self.0.push(Mistake {
            code,
            path: path.into(),
            message: message.into(),
        });
    }
}

fn string(value: Option<&Value>) -> Option<&str> {
    value.and_then(Value::as_str)
}

fn int(value: &Value) -> Option<i64> {
    value.as_i64()
}

/// Three finite numbers.
fn vector(value: Option<&Value>) -> Option<[f64; 3]> {
    let items = value?.as_array().filter(|a| a.len() == 3)?;
    let mut out = [0.0; 3];
    for (slot, item) in out.iter_mut().zip(items) {
        *slot = item.as_f64().filter(|n| n.is_finite())?;
    }
    Some(out)
}

/// What's wrong with `model`, a model file's JSON, for the game.
pub fn validate(model: &Value) -> Vec<Mistake> {
    let mut out = Mistakes(Vec::new());
    let Some(model) = model.as_object() else {
        out.with(
            codes::RESOURCE_PACK_MODEL_FILE,
            "$",
            "A model is a JSON object with elements, as Blockbench exports one",
        );
        return out.0;
    };
    let parent = model.get("parent");
    if parent.is_some_and(|p| !p.is_string()) {
        out.add("$.parent", "parent is a model's id");
    }
    let vars = model.get("textures");
    if vars.is_some_and(|v| !v.is_object()) {
        out.add(
            "$.textures",
            "textures is an object of names and textures: { \"0\": \"gems/ruby\" }",
        );
    }
    let empty = Map::new();
    let named = vars.and_then(Value::as_object).unwrap_or(&empty);
    for (name, value) in named {
        let at = child_path("$.textures", name);
        let Some(text) = value.as_str() else {
            out.add(at, "A texture is a string: a texture in this pack's textures/ (gems/ruby), the game's, or #another");
            continue;
        };
        // A namespace other than the game's is one nothing serves: the pack's are named by their path alone.
        if let Some((namespace, path)) = text.split_once(':')
            && !text.starts_with('#')
            && !namespace.is_empty()
            && namespace != game::NAMESPACE
        {
            out.with(
                codes::RESOURCE_PACK_MODEL_TEXTURE,
                at,
                format!(
                    "\"{text}\" is in no namespace the client has: name this pack's textures by their path under textures/ \
                     (\"{path}\" for textures/{path}.png), the game's with {}:",
                    game::NAMESPACE
                ),
            );
        }
    }
    match model.get("elements") {
        Some(Value::Array(elements)) => {
            for (index, item) in elements.iter().enumerate() {
                element(
                    item,
                    &format!("$.elements[{index}]"),
                    named,
                    parent.is_some(),
                    &mut out,
                );
            }
        }
        Some(_) => out.add("$.elements", "elements is a list of boxes"),
        None => {}
    }
    match model.get("display") {
        Some(Value::Object(display)) => {
            for (context, transform) in display {
                self::display(transform, &child_path("$.display", context), &mut out);
            }
        }
        Some(_) => out.add(
            "$.display",
            "display is an object of transforms by where the item is shown",
        ),
        None => {}
    }
    if model
        .get("ambientocclusion")
        .is_some_and(|v| !v.is_boolean())
    {
        out.add("$.ambientocclusion", "ambientocclusion is true or false");
    }
    out.0
}

fn element(
    item: &Value,
    at: &str,
    textures: &Map<String, Value>,
    has_parent: bool,
    out: &mut Mistakes,
) {
    let Some(element) = item.as_object() else {
        out.add(
            at,
            "An element is an object: { \"from\", \"to\", \"faces\" }",
        );
        return;
    };
    for corner in ["from", "to"] {
        let path = format!("{at}.{corner}");
        match vector(element.get(corner)) {
            None => out.add(path, format!("{corner} is three numbers, [x, y, z] in sixteenths of a block")),
            Some(point) if point.iter().any(|c| *c < MIN_COORDINATE || *c > MAX_COORDINATE) => out.with(
                codes::RESOURCE_PACK_MODEL_BOUNDS,
                path,
                format!(
                    "{corner} is outside {} to {}, which the game refuses to load (the whole model then draws as missing): \
                     scale the model down in its display, or split it into parts",
                    format_number(MIN_COORDINATE),
                    format_number(MAX_COORDINATE)
                ),
            ),
            Some(_) => {}
        }
    }
    if let Some(rotation) = element.get("rotation") {
        self::rotation(rotation, &format!("{at}.rotation"), out);
    }
    if element.get("shade").is_some_and(|v| !v.is_boolean()) {
        out.add(format!("{at}.shade"), "shade is true or false");
    }
    if element
        .get("light_emission")
        .is_some_and(|v| int(v).is_none_or(|n| !(0..=15).contains(&n)))
    {
        out.add(
            format!("{at}.light_emission"),
            "light_emission is a whole number from 0 to 15",
        );
    }
    let Some(faces) = element
        .get("faces")
        .and_then(Value::as_object)
        .filter(|f| !f.is_empty())
    else {
        out.add(
            format!("{at}.faces"),
            "An element needs faces: { \"north\": { \"texture\": \"#0\" }, … }",
        );
        return;
    };
    for (name, value) in faces {
        let face = child_path(&format!("{at}.faces"), name);
        if !FACES.contains(&name.as_str()) {
            out.add(
                face,
                format!("\"{name}\" isn't a face: {}", FACES.join(", ")),
            );
            continue;
        }
        let body = value.as_object();
        let Some((body, texture)) = body.and_then(|b| string(b.get("texture")).map(|t| (b, t)))
        else {
            out.add(
                face,
                "A face is { \"texture\": \"#0\" }, and maybe uv, rotation, cullface and tintindex",
            );
            continue;
        };
        // With a parent, the parent may give the variable: only a model on its own can be held to its own.
        if !has_parent && texture.starts_with('#') && resolve(texture, textures).is_none() {
            out.with(
                codes::RESOURCE_PACK_MODEL_TEXTURE,
                format!("{face}.texture"),
                format!("\"{texture}\" is a texture this model doesn't give: add \"{}\" to its textures", &texture[1..]),
            );
        }
        if let Some(uv) = body.get("uv")
            && uv
                .as_array()
                .is_none_or(|a| a.len() != 4 || a.iter().any(|n| !n.is_number()))
        {
            out.add(
                format!("{face}.uv"),
                "uv is four numbers: [x1, y1, x2, y2] on its texture, in sixteenths",
            );
        }
        if body
            .get("rotation")
            .is_some_and(|r| int(r).is_none_or(|n| ![0, 90, 180, 270].contains(&n)))
        {
            out.add(
                format!("{face}.rotation"),
                "A face's rotation is 0, 90, 180 or 270",
            );
        }
        if body
            .get("cullface")
            .is_some_and(|c| c.as_str().is_none_or(|c| !FACES.contains(&c)))
        {
            out.add(
                format!("{face}.cullface"),
                format!("cullface is a face: {}", FACES.join(", ")),
            );
        }
        if body.get("tintindex").is_some_and(|t| int(t).is_none()) {
            out.add(format!("{face}.tintindex"), "tintindex is a whole number");
        }
    }
}

/// One axis and any angle, or any angles about all three; with an origin either way.
fn rotation(value: &Value, at: &str, out: &mut Mistakes) {
    let Some(rotation) = value.as_object() else {
        out.add(at, ROTATION_RULE);
        return;
    };
    if vector(rotation.get("origin")).is_none() {
        out.add(
            format!("{at}.origin"),
            "origin is three numbers, the point it turns about",
        );
    }
    let axis = string(rotation.get("axis"));
    let angle = rotation.get("angle").and_then(Value::as_f64);
    let single = rotation.contains_key("axis") || rotation.contains_key("angle");
    let euler_ok = AXES.iter().all(|a| {
        rotation
            .get(*a)
            .and_then(Value::as_f64)
            .is_some_and(f64::is_finite)
    });
    if single && (axis.is_none_or(|a| !AXES.contains(&a)) || angle.is_none_or(|a| !a.is_finite())) {
        out.add(at, ROTATION_RULE);
    } else if single && AXES.iter().any(|a| rotation.contains_key(*a)) {
        out.add(
            at,
            "A rotation is one axis and an angle or x, y and z, not both",
        );
    } else if !single && !euler_ok {
        out.add(at, ROTATION_RULE);
    }
    if rotation.get("rescale").is_some_and(|v| !v.is_boolean()) {
        out.add(format!("{at}.rescale"), "rescale is true or false");
    }
}

/// A display transform: rotation, translation and scale, the last two as far as the game takes them.
fn display(value: &Value, at: &str, out: &mut Mistakes) {
    let Some(transform) = value.as_object() else {
        out.add(
            at,
            "A display transform is { \"rotation\", \"translation\", \"scale\" }",
        );
        return;
    };
    for part in ["rotation", "translation", "scale"] {
        let Some(raw) = transform.get(part) else {
            continue;
        };
        let Some(numbers) = vector(Some(raw)) else {
            out.add(format!("{at}.{part}"), format!("{part} is three numbers"));
            continue;
        };
        let limit = match part {
            "translation" => MAX_TRANSLATION,
            "scale" => MAX_SCALE,
            _ => continue,
        };
        if numbers.iter().any(|n| *n < -limit || *n > limit) {
            out.with(
                codes::RESOURCE_PACK_MODEL_DISPLAY,
                format!("{at}.{part}"),
                format!(
                    "The game clamps a display's {part} to ±{}, so it won't be what this says: scale the centity node (or the model's elements) instead",
                    format_number(limit)
                ),
            );
        }
    }
}

/// `#name` followed through the model's own textures to a texture, or `None` when it leads nowhere (or loops).
fn resolve<'a>(reference: &'a str, textures: &'a Map<String, Value>) -> Option<&'a str> {
    let mut at = reference;
    for _ in 0..MAX_TEXTURE_CHAIN {
        let Some(name) = at.strip_prefix('#') else {
            return Some(at);
        };
        at = textures.get(name)?.as_str()?;
    }
    None
}
