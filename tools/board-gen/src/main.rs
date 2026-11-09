//! Rebuilds the checked-in atlas from public-domain Natural Earth geography.
//!
//! Usage: `cargo run --release -p board-gen [cache-directory]`
//!
//! Writes `assets/board.json` and `docs/TERRITORIES.md`. Geometry runs on GEOS 3.14.1, compiled
//! from source, so the output matches the original Shapely-based generator byte for byte.
//! Nothing here is needed to build or play the game.

use geos::{BufferParams, CapStyle, CoordSeq, Geom, Geometry, GeometryTypes, JoinStyle};
use serde_json::Value;
use sha2::{Digest, Sha256};
use std::error::Error;
use std::fmt::Write as _;
use std::fs;
use std::path::{Path, PathBuf};

type R<T> = Result<T, Box<dyn Error>>;

const BASE: &str = "https://raw.githubusercontent.com/nvkelso/natural-earth-vector/\
                    ca96624a56bd078437bca8184e78163e5039ad19/geojson/";
const SOURCES: [(&str, &str, &str); 2] = [
    (
        "countries",
        "ne_50m_admin_0_countries",
        "3e458fc036ad0a66411f2c1e6cac49c5d7bfb81cb1123bc513b22511a2b7fdeb",
    ),
    (
        "provinces",
        "ne_10m_admin_1_states_provinces",
        "22d0e3ad85eb3e27f17cabf8ba2d50e554fbc27a87796ff891d958185da62fb5",
    ),
];
const CONTINENTS: [(&str, &str); 7] = [
    ("North America", "D5AD66"),
    ("South America", "80B593"),
    ("Europe", "8CB9CE"),
    ("Africa", "D19271"),
    ("Asia", "B2A3CE"),
    ("Australia", "D2BE7B"),
    ("Middle-East", "C48D9C"),
];

/// A number that prints the way Python's `json.dumps` prints it: integers stay integers.
#[derive(Clone, Copy, Debug)]
enum Num {
    Int(i64),
    Float(f64),
}

use Num::{Float as F, Int as I};

impl Num {
    /// `(v + 180) * 5` or `(v + 60) * 5`, then Python's `round(_, 2)`.
    fn scaled(self, offset: i64) -> Num {
        match self {
            I(v) => I((v + offset) * 5),
            F(v) => F(round2((v + offset as f64) * 5.0)),
        }
    }
    fn json(self) -> String {
        match self {
            I(v) => v.to_string(),
            F(v) => python_float(v),
        }
    }
}

/// Python's `round(x, 2)`: correctly rounded to two decimals, ties to even.
fn round2(v: f64) -> f64 {
    format!("{v:.2}").parse().expect("formatted float parses")
}

/// Python's `repr(float)` for the magnitudes on this board.
fn python_float(v: f64) -> String {
    if v.is_finite() && v.fract() == 0.0 && v.abs() < 1e16 {
        format!("{v:.1}")
    } else {
        format!("{v}")
    }
}

fn xy(p: (Num, Num)) -> (Num, Num) {
    (p.0.scaled(180), p.1.scaled(60))
}

/// Python's `json.dumps` string escaping (ASCII only).
fn json_str(s: &str) -> String {
    let mut out = String::from("\"");
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            '\u{8}' => out.push_str("\\b"),
            '\u{c}' => out.push_str("\\f"),
            c if (c as u32) < 0x20 || (c as u32) > 0x7f => {
                let mut buf = [0u16; 2];
                for unit in c.encode_utf16(&mut buf) {
                    let _ = write!(out, "\\u{unit:04x}");
                }
            }
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

fn point_json(p: (Num, Num)) -> String {
    format!("[{},{}]", p.0.json(), p.1.json())
}

// ------------------------------------------------------------------ geometry helpers

fn coords(v: &Value) -> R<Vec<[f64; 2]>> {
    v.as_array()
        .ok_or("coordinates must be an array")?
        .iter()
        .map(|p| {
            let p = p.as_array().ok_or("point must be an array")?;
            Ok([p[0].as_f64().ok_or("bad x")?, p[1].as_f64().ok_or("bad y")?])
        })
        .collect()
}

fn ring(v: &Value) -> R<Geometry> {
    Ok(Geometry::create_linear_ring(CoordSeq::new_from_vec(
        &coords(v)?,
    )?)?)
}

fn polygon(rings: &Value) -> R<Geometry> {
    let rings = rings.as_array().ok_or("polygon must be an array")?;
    let holes = rings[1..].iter().map(ring).collect::<R<Vec<_>>>()?;
    Ok(Geometry::create_polygon(ring(&rings[0])?, holes)?)
}

/// Shapely's `shape()` for the GeoJSON polygons Natural Earth uses.
fn shape(geometry: &Value) -> R<Geometry> {
    let c = &geometry["coordinates"];
    match geometry["type"].as_str() {
        Some("Polygon") => polygon(c),
        Some("MultiPolygon") => Ok(Geometry::create_multipolygon(
            c.as_array()
                .ok_or("multipolygon must be an array")?
                .iter()
                .map(polygon)
                .collect::<R<Vec<_>>>()?,
        )?),
        other => Err(format!("unsupported geometry type {other:?}").into()),
    }
}

/// Shapely's `box(minx, miny, maxx, maxy)`, counter-clockwise from (maxx, miny).
fn bbox(minx: f64, miny: f64, maxx: f64, maxy: f64) -> R<Geometry> {
    let ring = CoordSeq::new_from_vec(&[
        [maxx, miny],
        [maxx, maxy],
        [minx, maxy],
        [minx, miny],
        [maxx, miny],
    ])?;
    Ok(Geometry::create_polygon(
        Geometry::create_linear_ring(ring)?,
        vec![],
    )?)
}

fn unary_union(geoms: Vec<Geometry>) -> R<Geometry> {
    Ok(Geometry::create_geometry_collection(geoms)?.unary_union()?)
}

/// Shapely's `buffer(distance)`: 8 segments per quarter circle, round caps and joins.
fn buffer(g: &Geometry, distance: f64) -> R<Geometry> {
    let params = BufferParams::builder()
        .quadrant_segments(8)
        .end_cap_style(CapStyle::Round)
        .join_style(JoinStyle::Round)
        .mitre_limit(5.0)
        .single_sided(false)
        .build()?;
    Ok(g.buffer_with_params(distance, &params)?)
}

/// The parts of a multi-geometry or collection; any other geometry is its own single part.
fn parts(g: &Geometry) -> R<Vec<Geometry>> {
    Ok(match g.geometry_type()? {
        GeometryTypes::MultiPolygon
        | GeometryTypes::MultiLineString
        | GeometryTypes::MultiPoint
        | GeometryTypes::GeometryCollection => (0..g.get_num_geometries()?)
            .map(|i| Ok(Geom::clone(&g.get_geometry_n(i)?)?))
            .collect::<R<Vec<_>>>()?,
        _ => vec![Clone::clone(g)],
    })
}

// ------------------------------------------------------------------ sources

fn cached_source(cache: &Path, name: &str, source: &str, digest: &str) -> R<Vec<Value>> {
    let path = cache.join(format!("{name}.json"));
    if !path.exists() {
        eprintln!("Downloading {source}…");
        let mut response = ureq::get(&format!("{BASE}{source}.geojson")).call()?;
        let bytes = response
            .body_mut()
            .with_config()
            .limit(200 << 20)
            .read_to_vec()?;
        fs::write(&path, bytes)?;
    }
    let bytes = fs::read(&path)?;
    let actual = Sha256::digest(&bytes)
        .iter()
        .fold(String::new(), |mut s, b| {
            let _ = write!(s, "{b:02x}");
            s
        });
    if actual != digest {
        return Err(format!(
            "Source checksum mismatch: {}; remove it and retry the pinned download.",
            path.display()
        )
        .into());
    }
    let data: Value = serde_json::from_slice(&bytes)?;
    Ok(data["features"]
        .as_array()
        .ok_or("missing features")?
        .clone())
}

fn prop<'a>(f: &'a Value, key: &str) -> &'a str {
    f["properties"][key].as_str().unwrap_or("")
}

// ------------------------------------------------------------------ territories

struct Territory {
    name: String,
    continent: String,
    geometry: Geometry,
    includes: String,
}

/// Territories in insertion order; re-adding a name replaces it in place, like a Python dict.
#[derive(Default)]
struct Atlas {
    territories: Vec<Territory>,
}

impl Atlas {
    fn find(&self, name: &str) -> Option<usize> {
        self.territories.iter().position(|t| t.name == name)
    }

    fn get(&mut self, name: &str) -> &mut Territory {
        let i = self
            .find(name)
            .unwrap_or_else(|| panic!("unknown territory {name}"));
        &mut self.territories[i]
    }

    fn add(
        &mut self,
        name: &str,
        continent: &str,
        geometries: Vec<Geometry>,
        includes: Option<String>,
    ) -> R<()> {
        let territory = Territory {
            name: name.into(),
            continent: continent.into(),
            geometry: buffer(&unary_union(geometries)?, 0.0)?,
            includes: includes.unwrap_or_else(|| name.into()),
        };
        match self.find(name) {
            Some(i) => self.territories[i] = territory,
            None => self.territories.push(territory),
        }
        Ok(())
    }

    fn add_features(
        &mut self,
        name: &str,
        continent: &str,
        features: &[&Value],
        includes: Option<String>,
    ) -> R<()> {
        let geometries = features
            .iter()
            .map(|f| shape(&f["geometry"]))
            .collect::<R<Vec<_>>>()?;
        self.add(name, continent, geometries, includes)
    }

    fn group(&mut self, countries: &[Value], name: &str, continent: &str, codes: &str) -> R<()> {
        let codes: Vec<&str> = codes.split_whitespace().collect();
        let features: Vec<&Value> = countries
            .iter()
            .filter(|f| codes.contains(&prop(f, "ADM0_A3")))
            .collect();
        let includes = features
            .iter()
            .map(|f| prop(f, "ADMIN"))
            .collect::<Vec<_>>()
            .join(", ");
        self.add_features(name, continent, &features, Some(includes))
    }
}

/// A sea lane: its two territories and the ocean waypoints it passes through.
type SeaLane = (&'static str, &'static str, Vec<(Num, Num)>);

struct Edge {
    a: String,
    b: String,
    kind: &'static str,
    via: Vec<(Num, Num)>,
}

fn link(edges: &mut Vec<Edge>, a: &str, b: &str, kind: &'static str, via: Vec<(Num, Num)>) {
    edges.retain(|e| !((e.a == a && e.b == b) || (e.a == b && e.b == a)));
    edges.push(Edge {
        a: a.into(),
        b: b.into(),
        kind,
        via,
    });
}

fn main() -> R<()> {
    let root = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()?;
    let cache = std::env::args()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(|| root.join("tools/cache"));
    fs::create_dir_all(&cache)?;
    let countries = cached_source(&cache, SOURCES[0].0, SOURCES[0].1, SOURCES[0].2)?;
    let provinces = cached_source(&cache, SOURCES[1].0, SOURCES[1].1, SOURCES[1].2)?;
    let mut atlas = Atlas::default();

    for f in &provinces {
        if prop(f, "admin") == "Canada" {
            let name = match prop(f, "name") {
                "Québec" => "Quebec",
                other => other,
            };
            atlas.add_features(name, "North America", &[f], None)?;
        }
    }
    for (name, region) in [
        ("US-West", "West"),
        ("US-Midwest", "Midwest"),
        ("US-East", "Northeast"),
        ("US-South", "South"),
    ] {
        let fs: Vec<&Value> = provinces
            .iter()
            .filter(|f| {
                prop(f, "admin") == "United States of America"
                    && prop(f, "region") == region
                    && !["Alaska", "Hawaii"].contains(&prop(f, "name"))
            })
            .collect();
        let includes = fs
            .iter()
            .map(|f| prop(f, "name"))
            .collect::<Vec<_>>()
            .join(", ");
        atlas.add_features(name, "North America", &fs, Some(includes))?;
    }
    for name in ["Alaska", "Hawaii"] {
        let fs: Vec<&Value> = provinces
            .iter()
            .filter(|f| prop(f, "admin") == "United States of America" && prop(f, "name") == name)
            .collect();
        atlas.add_features(name, "North America", &fs, None)?;
    }
    atlas.group(
        &countries,
        "Central America",
        "North America",
        "MEX GTM BLZ HND SLV NIC CRI PAN",
    )?;
    atlas.group(
        &countries,
        "Cuba",
        "North America",
        "CUB JAM HTI DOM BHS PRI TCA CYM",
    )?;
    atlas.group(&countries, "Greenland", "North America", "GRL")?;
    for (name, codes) in [
        ("Brazil", "BRA GUY SUR PRY"),
        ("Argentina", "ARG CHL URY FLK"),
        ("Peru", "PER ECU BOL"),
        ("Colombia", "COL"),
        ("Venezuela", "VEN TTO ABW CUW"),
    ] {
        atlas.group(&countries, name, "South America", codes)?;
    }
    for (name, codes) in [
        ("Spain", "ESP PRT AND"),
        ("France", "FRA BEL LUX MCO"),
        ("Germany", "DEU NLD CHE AUT LIE"),
        ("Poland", "POL CZE SVK HUN"),
        ("England", "GBR IRL IMN JEY GGY"),
        ("Iceland", "ISL"),
        ("Nordics", "NOR SWE FIN DNK ALD FRO"),
        (
            "Italy",
            "ITA SMR VAT SVN HRV BIH SRB KOS MNE ALB MKD GRC MLT",
        ),
        ("Ukraine", "UKR BLR MDA ROU BGR"),
        ("Baltics", "EST LVA LTU"),
    ] {
        atlas.group(&countries, name, "Europe", codes)?;
    }
    for (name, codes) in [
        ("Arabia", "SAU YEM OMN ARE QAT BHR KWT"),
        ("Persia", "IRN IRQ AZE"),
        ("Syria", "SYR LBN ISR PSX JOR"),
        ("Turkey", "TUR GEO ARM CYP CYN"),
    ] {
        atlas.group(&countries, name, "Middle-East", codes)?;
    }
    for (name, codes) in [
        ("Philippines", "PHL PLW"),
        ("India", "IND PAK BGD NPL BTN LKA MDV KAS"),
        ("Central Asia", "KAZ AFG UZB TKM KGZ TJK"),
        ("Mongolia", "MNG"),
        ("Thailand", "THA MMR LAO KHM VNM MYS SGP"),
        ("China", "CHN TWN HKG MAC"),
        ("Korea", "KOR PRK"),
        ("Japan", "JPN"),
        ("Indonesia", "IDN BRN TLS PNG"),
    ] {
        atlas.group(&countries, name, "Asia", codes)?;
    }
    // Group Russian federal subjects into legible board regions, with Yakutsk and
    // Irkutsk named for the familiar territories requested by the author.
    let russian_names = [
        "Russia", "Urals", "Siberia", "Irkutsk", "Yakutsk", "Far East",
    ];
    let mut russian: Vec<Vec<&Value>> = vec![Vec::new(); russian_names.len()];
    for f in &provinces {
        if prop(f, "admin") != "Russia" || ["Crimea", "Sevastopol"].contains(&prop(f, "name")) {
            continue;
        }
        let name = match (prop(f, "region"), prop(f, "name")) {
            ("Urals", _) => "Urals",
            ("Siberian", _) if prop(f, "region_sub") == "East Siberian" => "Irkutsk",
            ("Siberian", _) => "Siberia",
            (_, "Sakha (Yakutia)") => "Yakutsk",
            ("Far Eastern", _) => "Far East",
            _ => "Russia",
        };
        russian[russian_names
            .iter()
            .position(|n| *n == name)
            .expect("known group")]
        .push(f);
    }
    for (name, fs) in russian_names.iter().zip(&russian) {
        let includes = fs
            .iter()
            .map(|f| {
                f["properties"]["name"]
                    .as_str()
                    .filter(|n| !n.is_empty())
                    .unwrap_or("Tyumen subdivision")
            })
            .collect::<Vec<_>>()
            .join(", ");
        atlas.add_features(
            name,
            if *name == "Russia" { "Europe" } else { "Asia" },
            fs,
            Some(includes),
        )?;
    }
    for name in [
        "Western Australia",
        "Northern Territory",
        "South Australia",
        "Queensland",
        "New South Wales",
        "Victoria",
    ] {
        let mut names = vec![name];
        if name == "New South Wales" {
            names.extend([
                "Australian Capital Territory",
                "Jervis Bay Territory",
                "Lord Howe Island",
            ]);
        }
        if name == "Victoria" {
            names.extend(["Tasmania", "Macquarie Island"]);
        }
        let fs: Vec<&Value> = provinces
            .iter()
            .filter(|f| prop(f, "admin") == "Australia" && names.contains(&prop(f, "name")))
            .collect();
        atlas.add_features(name, "Australia", &fs, Some(names.join(", ")))?;
    }
    atlas.group(
        &countries,
        "New Zealand",
        "Australia",
        "NZL NFK NCL FJI VUT SLB",
    )?;
    for (name, codes) in [
        ("Egypt", "EGY LBY SDN SDS"),
        ("Ethiopia", "ETH ERI DJI SOM SOL KEN UGA TZA"),
        ("Congo", "COD COG GAB GNQ AGO RWA BDI"),
        ("Southern Africa", "ZAF NAM BWA ZWE ZMB MWI MOZ SWZ LSO"),
        ("Madagascar", "MDG COM MUS SYC"),
        ("Algeria", "DZA MAR TUN SAH"),
        ("Nigeria", "NGA BEN TGO GHA CIV"),
        ("Mali", "MLI MRT SEN GMB GNB GIN SLE LBR BFA NER CPV"),
    ] {
        atlas.group(&countries, name, "Africa", codes)?;
    }
    // Requested custom borders: Chad is its own territory with Cameroon, the Central African
    // Republic and eastern Niger (east of 10 E); northern Mauritania (above 22 N) belongs to Algeria.
    let country = |code: &str| -> R<Geometry> {
        shape(
            &countries
                .iter()
                .find(|f| prop(f, "ADM0_A3") == code)
                .ok_or("missing country")?["geometry"],
        )
    };
    let eastern_niger = country("NER")?.intersection(&bbox(10.0, -90.0, 180.0, 90.0)?)?;
    atlas.add(
        "Chad",
        "Africa",
        vec![
            country("TCD")?,
            country("CMR")?,
            country("CAF")?,
            Clone::clone(&eastern_niger),
        ],
        Some("Chad, Cameroon, Central African Republic, eastern Niger (east of 10 E)".into()),
    )?;
    let mali = atlas.get("Mali");
    mali.geometry = buffer(&mali.geometry.difference(&eastern_niger)?, 0.0)?;
    mali.includes = mali
        .includes
        .replace("Niger", "western Niger (west of 10 E)");
    let northern_mauritania = country("MRT")?.intersection(&bbox(-180.0, 22.0, 180.0, 90.0)?)?;
    let mali = atlas.get("Mali");
    mali.geometry = mali.geometry.difference(&northern_mauritania)?;
    mali.includes = mali
        .includes
        .replace("Mauritania", "southern Mauritania (south of 22 N)");
    let algeria = atlas.get("Algeria");
    algeria.geometry = buffer(
        &unary_union(vec![Clone::clone(&algeria.geometry), northern_mauritania])?,
        0.0,
    )?;
    algeria
        .includes
        .push_str(", northern Mauritania (north of 22 N)");
    // Move overseas country components to their geographic neighbours, e.g. French Guiana into
    // Brazil. The main land mass remains with its assigned territory.
    let europe = bbox(-20.0, 30.0, 30.0, 65.0)?;
    let france = atlas.get("France");
    let overseas = france.geometry.difference(&europe)?;
    france.geometry = france.geometry.intersection(&europe)?;
    for part in parts(&overseas)? {
        if part.is_empty()? {
            continue;
        }
        let centroid = part.get_centroid()?;
        let (cx, cy) = (centroid.get_x()?, centroid.get_y()?);
        let target = if cy < 10.0 && cx < 0.0 {
            "Brazil"
        } else if cx < 0.0 {
            "Cuba"
        } else {
            "Madagascar"
        };
        let t = atlas.get(target);
        t.geometry = unary_union(vec![Clone::clone(&t.geometry), part])?;
        t.includes.push_str(", nearby French overseas islands/land");
    }
    // Remaining small islands are absorbed into the nearest regional territory.
    let mut assigned: Vec<String> = "CAN USA AUS RUS ATA ATF HMD SGS TCD CMR CAF NER MRT"
        .split(' ')
        .map(String::from)
        .collect();
    for t in &atlas.territories {
        let parts: Vec<&str> = t.includes.split(", ").collect();
        for f in &countries {
            if parts.contains(&prop(f, "ADMIN")) {
                assigned.push(prop(f, "ADM0_A3").to_string());
            }
        }
    }
    for f in &countries {
        if assigned.iter().any(|a| a == prop(f, "ADM0_A3")) {
            continue;
        }
        let g = shape(&f["geometry"])?;
        if g.area()? > 8.0 {
            return Err(format!("Unassigned large country: {}", prop(f, "ADMIN")).into());
        }
        let mut nearest = 0;
        let mut best = f64::INFINITY;
        for (i, t) in atlas.territories.iter().enumerate() {
            let d = t.geometry.distance(&g)?;
            if d < best {
                best = d;
                nearest = i;
            }
        }
        let t = &mut atlas.territories[nearest];
        t.geometry = buffer(&unary_union(vec![Clone::clone(&t.geometry), g])?, 0.0)?;
        let _ = write!(t.includes, ", {}", prop(f, "ADMIN"));
    }
    // Each endpoint is an actual territory. Land edges come from shared boundaries;
    // sea edges below are a deliberate, modest expansion of classic routes.
    let mut edges = Vec::new();
    let ts = &atlas.territories;
    let boundaries = ts
        .iter()
        .map(|t| t.geometry.boundary())
        .collect::<Result<Vec<_>, _>>()?;
    for i in 0..ts.len() {
        for j in i + 1..ts.len() {
            let (ga, gb) = (&ts[i].geometry, &ts[j].geometry);
            let touching = boundaries[i].intersection(&boundaries[j])?.length()? > 0.05
                || buffer(ga, 0.012)?.intersection(gb)?.area()? > 0.008;
            if touching {
                edges.push(Edge {
                    a: ts[i].name.clone(),
                    b: ts[j].name.clone(),
                    kind: "LAND",
                    via: vec![],
                });
            }
        }
    }
    // Some sources use different shoreline resolution at international borders.
    for (a, b) in [
        ("Alaska", "Yukon"),
        ("Alaska", "British Columbia"),
        ("US-West", "British Columbia"),
        ("US-West", "Alberta"),
        ("US-West", "Saskatchewan"),
        ("US-Midwest", "Saskatchewan"),
        ("US-Midwest", "Manitoba"),
        ("US-Midwest", "Ontario"),
        ("US-East", "Ontario"),
        ("US-East", "Quebec"),
        ("US-East", "New Brunswick"),
        ("US-West", "Central America"),
        ("US-South", "Central America"),
        ("Russia", "Urals"),
        ("Russia", "Ukraine"),
        ("Russia", "Baltics"),
        ("Russia", "Nordics"),
        ("Russia", "Central Asia"),
        ("Russia", "Turkey"),
        ("Urals", "Central Asia"),
        ("Siberia", "Central Asia"),
        ("Siberia", "China"),
        ("Irkutsk", "Mongolia"),
        ("Irkutsk", "China"),
        ("Far East", "China"),
        ("Far East", "Korea"),
    ] {
        link(&mut edges, a, b, "LAND", vec![]);
    }
    // Geographic waypoints route lanes through ocean rather than across land.
    let sea: Vec<SeaLane> = vec![
        (
            "Alaska",
            "Far East",
            vec![(I(-176), I(66)), (I(176), I(66))],
        ),
        (
            "Hawaii",
            "US-West",
            vec![(I(-143), I(22)), (I(-125), I(30))],
        ),
        (
            "Hawaii",
            "Japan",
            vec![(I(-178), I(18)), (I(178), I(18)), (I(150), I(24))],
        ),
        ("Nunavut", "Greenland", vec![(I(-67), I(72))]),
        (
            "Newfoundland and Labrador",
            "Greenland",
            vec![(I(-43), I(56))],
        ),
        ("Greenland", "Iceland", vec![(I(-29), I(64))]),
        ("Iceland", "England", vec![(I(-14), I(58))]),
        ("Iceland", "Nordics", vec![(I(-2), I(68))]),
        ("England", "France", vec![(I(-4), I(49))]),
        ("England", "Nordics", vec![(I(2), I(58))]),
        ("Spain", "Algeria", vec![(I(-5), I(35))]),
        ("Italy", "Algeria", vec![(I(8), I(37))]),
        ("Italy", "Turkey", vec![(I(27), I(35))]),
        ("US-South", "Cuba", vec![(I(-83), I(25))]),
        ("Central America", "Cuba", vec![(I(-87), I(20))]),
        ("Cuba", "Venezuela", vec![(I(-70), I(17))]),
        ("Brazil", "Mali", vec![(I(-27), I(-1)), (I(-20), I(9))]),
        (
            "Nova Scotia",
            "Newfoundland and Labrador",
            vec![(I(-56), I(46))],
        ),
        (
            "Prince Edward Island",
            "New Brunswick",
            vec![(I(-64), F(46.5))],
        ),
        (
            "Prince Edward Island",
            "Nova Scotia",
            vec![(I(-62), F(45.8))],
        ),
        ("Arabia", "Ethiopia", vec![(I(45), I(12))]),
        ("Madagascar", "Southern Africa", vec![(I(40), I(-22))]),
        ("Madagascar", "Ethiopia", vec![(I(51), I(-5))]),
        ("India", "Arabia", vec![(I(62), I(17))]),
        ("India", "Indonesia", vec![(I(88), I(7)), (I(95), I(0))]),
        ("Korea", "Japan", vec![(I(130), I(36))]),
        ("Japan", "Far East", vec![(I(146), I(47))]),
        ("China", "Philippines", vec![(I(119), I(21))]),
        ("Philippines", "Indonesia", vec![(I(125), I(3))]),
        ("Thailand", "Indonesia", vec![(I(103), I(0))]),
        ("Indonesia", "Western Australia", vec![(I(117), I(-17))]),
        ("Indonesia", "Queensland", vec![(I(145), I(-11))]),
        ("New South Wales", "New Zealand", vec![(I(162), I(-34))]),
        ("Victoria", "New Zealand", vec![(I(159), I(-47))]),
    ];
    for (a, b, via) in &sea {
        link(&mut edges, a, b, "SEA", via.clone());
    }
    let anchors: Vec<(&str, (Num, Num))> = vec![
        ("Alaska", (I(-151), I(64))),
        ("Yukon", (I(-135), I(64))),
        ("Northwest Territories", (I(-120), I(65))),
        ("Nunavut", (I(-96), I(69))),
        ("British Columbia", (I(-125), I(54))),
        ("Alberta", (I(-115), I(55))),
        ("Saskatchewan", (I(-106), I(55))),
        ("Manitoba", (I(-97), I(55))),
        ("Ontario", (I(-85), I(50))),
        ("Quebec", (I(-72), I(54))),
        ("Newfoundland and Labrador", (I(-58), I(53))),
        ("New Brunswick", (I(-66), F(46.6))),
        ("Nova Scotia", (I(-63), I(45))),
        ("Prince Edward Island", (F(-63.2), F(46.4))),
        ("US-West", (I(-114), I(39))),
        ("US-Midwest", (I(-94), I(42))),
        ("US-East", (I(-74), I(42))),
        ("US-South", (I(-88), I(34))),
        ("Central America", (I(-100), I(22))),
        ("Cuba", (I(-78), I(22))),
        ("Hawaii", (I(-156), I(20))),
        ("Greenland", (I(-42), I(73))),
        ("Brazil", (I(-52), I(-10))),
        ("Argentina", (I(-66), I(-36))),
        ("Peru", (I(-73), I(-13))),
        ("Colombia", (I(-73), I(4))),
        ("Venezuela", (I(-65), I(7))),
        ("Iceland", (I(-19), I(65))),
        ("England", (I(-3), I(55))),
        ("Nordics", (I(18), I(65))),
        ("France", (I(2), I(47))),
        ("Spain", (I(-4), I(40))),
        ("Germany", (I(10), I(50))),
        ("Poland", (I(20), I(51))),
        ("Italy", (I(16), I(42))),
        ("Ukraine", (I(30), I(49))),
        ("Baltics", (I(25), I(58))),
        ("Russia", (I(44), I(60))),
        ("Urals", (I(66), I(63))),
        ("Siberia", (I(82), I(57))),
        ("Irkutsk", (I(103), I(60))),
        ("Yakutsk", (I(128), I(65))),
        ("Far East", (I(158), I(61))),
        ("Turkey", (I(35), I(40))),
        ("Syria", (I(37), I(32))),
        ("Arabia", (I(46), I(24))),
        ("Persia", (I(52), I(33))),
        ("Central Asia", (I(65), I(43))),
        ("India", (I(78), I(23))),
        ("Mongolia", (I(103), I(47))),
        ("China", (I(105), I(34))),
        ("Thailand", (I(100), I(17))),
        ("Korea", (I(127), I(40))),
        ("Japan", (I(139), I(37))),
        ("Philippines", (I(123), I(12))),
        ("Indonesia", (I(119), I(-4))),
        ("Egypt", (I(28), I(23))),
        ("Algeria", (I(3), I(28))),
        ("Mali", (I(-3), I(17))),
        ("Nigeria", (I(7), I(7))),
        ("Ethiopia", (I(40), I(6))),
        ("Chad", (F(18.5), I(13))),
        ("Congo", (I(22), I(-5))),
        ("Southern Africa", (I(25), I(-23))),
        ("Madagascar", (I(47), I(-20))),
        ("Western Australia", (I(122), I(-26))),
        ("Northern Territory", (I(133), I(-20))),
        ("South Australia", (I(135), I(-30))),
        ("Queensland", (I(145), I(-22))),
        ("New South Wales", (I(147), I(-32))),
        ("Victoria", (I(144), I(-37))),
        ("Tasmania", (I(147), I(-43))),
        ("New Zealand", (I(173), I(-41))),
    ];
    let labels = [
        ("Northwest Territories", "NORTHWEST\nTERRITORIES"),
        ("Newfoundland and Labrador", "NEWFOUNDLAND\n& LABRADOR"),
        ("British Columbia", "BRITISH\nCOLUMBIA"),
        ("Prince Edward Island", "P.E.I."),
        ("New Brunswick", "N.B."),
        ("Nova Scotia", "N.S."),
        ("Central America", "CENTRAL\nAMERICA"),
        ("Central Asia", "CENTRAL ASIA"),
        ("Southern Africa", "SOUTHERN\nAFRICA"),
        ("Western Australia", "WESTERN\nAUSTRALIA"),
        ("Northern Territory", "NORTHERN\nTERRITORY"),
        ("South Australia", "SOUTH\nAUSTRALIA"),
        ("New South Wales", "NEW SOUTH\nWALES"),
        ("New Zealand", "NEW\nZEALAND"),
    ];

    let mut territories_json = Vec::new();
    for t in &atlas.territories {
        let g = t.geometry.topology_preserve_simplify(0.12)?;
        let polys = if g.geometry_type()? == GeometryTypes::MultiPolygon {
            parts(&g)?
        } else {
            vec![g]
        };
        let mut rings = Vec::new();
        for poly in polys {
            if poly.geometry_type()? != GeometryTypes::Polygon || poly.is_empty()? {
                continue;
            }
            // Retain small islands, while dropping subpixel coastal specks.
            if poly.area()? < 0.012
                && !["Prince Edward Island", "Hawaii"].contains(&t.name.as_str())
            {
                continue;
            }
            // Cartographic lakes are filled for a clean tabletop surface.
            let exterior = poly.get_exterior_ring()?.get_coord_seq()?;
            let n = exterior.size()?;
            let mut values = Vec::new();
            for k in 0..n - 1 {
                let (x, y) = xy((F(exterior.get_x(k)?), F(exterior.get_y(k)?)));
                values.push(x.json());
                values.push(y.json());
            }
            rings.push(format!("[{}]", values.join(",")));
        }
        let anchor = anchors
            .iter()
            .find(|(n, _)| *n == t.name)
            .ok_or(format!("no anchor for {}", t.name))?
            .1;
        let label = labels
            .iter()
            .find(|(n, _)| *n == t.name)
            .map(|(_, l)| l.to_string())
            .unwrap_or_else(|| t.name.to_uppercase());
        territories_json.push(format!(
            "{{\"name\":{},\"continent\":{},\"includes\":{},\"id\":{},\"label\":{},\"anchor\":{},\"polygons\":[{}]}}",
            json_str(&t.name),
            json_str(&t.continent),
            json_str(&t.includes),
            json_str(&t.name.to_lowercase().replace(' ', "-")),
            json_str(&label),
            point_json(xy(anchor)),
            rings.join(",")
        ));
    }
    let edges_json: Vec<String> = edges
        .iter()
        .map(|e| {
            format!(
                "{{\"a\":{},\"b\":{},\"kind\":{},\"via\":[{}]}}",
                json_str(&e.a),
                json_str(&e.b),
                json_str(e.kind),
                e.via
                    .iter()
                    .map(|p| point_json(xy(*p)))
                    .collect::<Vec<_>>()
                    .join(",")
            )
        })
        .collect();
    let continents_json: Vec<String> = CONTINENTS
        .iter()
        .map(|(n, c)| format!("{{\"name\":{},\"color\":{}}}", json_str(n), json_str(c)))
        .collect();
    let board = format!(
        "{{\"width\":1800,\"height\":750,\"continents\":[{}],\"territories\":[{}],\"connections\":[{}]}}\n",
        continents_json.join(","),
        territories_json.join(","),
        edges_json.join(",")
    );
    fs::write(root.join("assets/board.json"), board)?;

    let mut lines = vec![
        "# Territory groupings".to_string(),
        String::new(),
        "These are gameplay regions, not a claim about sovereignty. Tiny offshore islands are grouped geographically. Antarctica and remote subantarctic islands are omitted. Canada has all ten provinces and all three northern territories. Australia has five mainland state territories plus Northern Territory; Tasmania is merged with Victoria, and ACT belongs to New South Wales. Tanzania belongs to Ethiopia. Chad is its own territory and also includes Cameroon, the Central African Republic and Niger east of 10 E; western Niger belongs to Mali. Northern Mauritania above 22 N belongs to Algeria; the remainder belongs to Mali. Belgium belongs to France. Duplicate US-East and Baltics entries are deduplicated, and US-West covers the western US. Colombia and Philippines use their standard spellings.".into(),
        String::new(),
        "Russian subjects are grouped as Russia (European districts), Urals, Siberia (western), Irkutsk (eastern Siberia), Yakutsk (Sakha), and Far East. This keeps the board readable while including the requested Yakutsk and Irkutsk.".into(),
        String::new(),
        "| Territory | Continent | Includes |".into(),
        "| --- | --- | --- |".into(),
    ];
    for t in &atlas.territories {
        lines.push(format!("| {} | {} | {} |", t.name, t.continent, t.includes));
    }
    lines.push(String::new());
    lines.push("## Sea routes".into());
    lines.push(String::new());
    lines.push(format!("{} bidirectional sea lanes. Dashed gold lines and endpoint rings distinguish them from solid land borders. Selecting a territory highlights only its routes; numbered lane tags in the inspector distinguish destinations. Pacific crossings wrap at both map edges.", sea.len()));
    lines.push(String::new());
    for (a, b, _) in &sea {
        lines.push(format!("- {a} ↔ {b}"));
    }
    fs::write(root.join("docs/TERRITORIES.md"), lines.join("\n") + "\n")?;
    println!(
        "{} territories, {} land borders, {} sea lanes",
        atlas.territories.len(),
        edges.len() - sea.len(),
        sea.len()
    );
    Ok(())
}
