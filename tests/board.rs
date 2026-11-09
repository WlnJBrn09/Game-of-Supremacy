use std::collections::{HashSet, VecDeque};
use supremacy::board::{Board, Kind};
use supremacy::ui::camera::AtlasCamera;

fn load() -> Board {
    Board::bundled()
}

fn continent<'a>(b: &'a Board, region: &str) -> &'a str {
    &b.continents[b.region(region).continent].name
}

#[test]
fn includes_requested_regions_and_seven_continents() {
    let b = load();
    assert_eq!(72, b.regions.len());
    assert_eq!(7, b.continents.len());
    for name in [
        "Prince Edward Island",
        "Newfoundland and Labrador",
        "Nunavut",
        "Alaska",
        "US-West",
        "US-East",
        "US-Midwest",
        "US-South",
        "Cuba",
        "Hawaii",
        "Yakutsk",
        "Irkutsk",
        "Colombia",
        "Philippines",
        "Victoria",
        "New Zealand",
    ] {
        assert!(b.find(name).is_some(), "{name}");
    }
    assert_eq!("Asia", continent(&b, "Yakutsk"));
    assert_eq!("Europe", continent(&b, "Russia"));
    assert!(b.region("India").includes.contains("Pakistan"));
    assert!(b.region("Spain").includes.contains("Portugal"));
    assert!(b.region("Brazil").includes.contains("Guyana"));
    let middle_east: HashSet<&str> = b
        .regions
        .iter()
        .filter(|r| b.continents[r.continent].name == "Middle-East")
        .map(|r| r.name.as_str())
        .collect();
    assert_eq!(
        HashSet::from(["Arabia", "Turkey", "Syria", "Persia"]),
        middle_east
    );
}

#[test]
fn adjacency_is_connected_symmetric_and_has_no_duplicate_links() {
    let b = load();
    let mut seen = HashSet::new();
    let mut queue = VecDeque::from([0usize]);
    while let Some(r) = queue.pop_front() {
        if seen.insert(r) {
            for e in b.links(r) {
                queue.push_back(e.other(r));
            }
        }
    }
    assert_eq!(b.regions.len(), seen.len());
    assert_eq!(34, b.sea_count());
    let mut pairs = HashSet::new();
    for (i, e) in b.connections.iter().enumerate() {
        assert!(b.links(e.a).any(|x| std::ptr::eq(x, &b.connections[i])));
        assert!(b.links(e.b).any(|x| std::ptr::eq(x, &b.connections[i])));
        assert_ne!(e.a, e.b);
        let mut names = [b.regions[e.a].name.as_str(), b.regions[e.b].name.as_str()];
        names.sort();
        assert!(pairs.insert(names));
    }
    let india = b.index("India");
    assert!(b
        .links(india)
        .any(|e| b.regions[e.other(india)].name == "Thailand" && e.kind == Kind::Land));
    assert!(b.links(b.index("Hawaii")).all(|e| e.kind == Kind::Sea));
    assert!(b.find("Tasmania").is_none());
    assert!(b.region("Victoria").includes.contains("Tasmania"));
    let victoria = b.index("Victoria");
    assert!(b
        .links(victoria)
        .any(|e| b.regions[e.other(victoria)].name == "New Zealand" && e.kind == Kind::Sea));
}

#[test]
fn every_polygon_triangulates_and_hit_tests_recognize_interior_points() {
    let b = load();
    for r in &b.regions {
        for (p, tris) in r.polygons.iter().zip(&r.triangles) {
            assert!(tris.len() >= 3, "{}", r.name);
            assert_eq!((p.len() / 2 - 2) * 3, tris.len(), "{}", r.name);
            assert!(p.iter().all(|v| v.is_finite()));
        }
    }
    for name in [
        "Brazil",
        "Alberta",
        "China",
        "Yakutsk",
        "Irkutsk",
        "Western Australia",
    ] {
        let r = b.region(name);
        assert!(r.contains(r.anchor.x, r.anchor.y), "{name}");
    }
    assert!(!b.region("Brazil").contains(0.0, 0.0));
}

fn assert_owner(board: &Board, expected: &str, longitude: f32, latitude: f32) {
    let owners: Vec<&str> = board
        .regions
        .iter()
        .filter(|r| r.contains((longitude + 180.0) * 5.0, (latitude + 60.0) * 5.0))
        .map(|r| r.name.as_str())
        .collect();
    assert_eq!(
        vec![expected],
        owners,
        "Assignment at {longitude}, {latitude}"
    );
}

#[test]
fn custom_african_borders_and_belgium_match_the_requested_assignments() {
    let b = load();
    assert_owner(&b, "France", 4.5, 50.5);
    assert_owner(&b, "Ethiopia", 35.0, -6.0);
    assert_owner(&b, "Victoria", 146.5, -42.0);
    // Chad is one territory with Cameroon, the Central African Republic and eastern Niger.
    assert_owner(&b, "Chad", 18.0, 20.0);
    assert_owner(&b, "Chad", 16.0, 12.0);
    assert_owner(&b, "Chad", 21.0, 12.0);
    assert_owner(&b, "Chad", 12.0, 5.0);
    assert_owner(&b, "Chad", 20.0, 6.0);
    assert_owner(&b, "Chad", 12.0, 17.0);
    assert_owner(&b, "Mali", 7.0, 15.0);
    assert_owner(&b, "Nigeria", 8.0, 9.0);
    assert_owner(&b, "Egypt", 25.0, 20.0);
    assert_eq!("Africa", continent(&b, "Chad"));
    let ethiopia = b.index("Ethiopia");
    // Ethiopia is one landmass plus coastal islands; no detached inland piece remains.
    assert_eq!(
        1,
        b.regions[ethiopia]
            .polygons
            .iter()
            .filter(|p| p.len() > 20)
            .count()
    );
    let chad = b.index("Chad");
    assert!(b.links(chad).all(|e| e.other(chad) != ethiopia));
    assert_owner(&b, "Algeria", -9.0, 23.0);
    assert_owner(&b, "Mali", -9.0, 19.0);
}

#[test]
fn camera_maintains_top_down_transform_and_clamps_zoom_and_pan() {
    let mut c = AtlasCamera::new();
    c.zoom_at(2.0, 636.0, 499.0);
    let before = c.world(600.0, 530.0);
    c.zoom_at(1.25, 600.0, 530.0);
    let after = c.world(600.0, 530.0);
    assert!((before.x - after.x).abs() < 0.001);
    assert!((before.y - after.y).abs() < 0.001);
    assert!((600.0 - c.screen_x(after.x)).abs() < 0.001);
    assert!((530.0 - c.screen_y(after.y)).abs() < 0.001);
    c.zoom_at(999.0, 636.0, 499.0);
    assert_eq!(6.0, c.zoom);
    c.pan(99999.0, -99999.0);
    assert!(c.center_x >= 0.0 && c.center_x <= 1800.0);
    assert!(c.center_y >= 0.0 && c.center_y <= 750.0);
    c.zoom_at(0.0001, 636.0, 499.0);
    assert_eq!(1.0, c.zoom);
    c.reset();
    assert_eq!(900.0, c.center_x);
    assert_eq!(375.0, c.center_y);
}

#[test]
fn rejects_broken_boards() {
    assert!(Board::parse("{}").is_err());
    let disconnected = r#"{"width":10,"height":10,"continents":[{"name":"A","color":"FFFFFF"}],
      "territories":[
        {"id":"a","name":"A1","label":"A1","includes":"","continent":"A","anchor":[1,1],"polygons":[[0,0,1,0,1,1]]},
        {"id":"b","name":"B1","label":"B1","includes":"","continent":"A","anchor":[1,1],"polygons":[[0,0,1,0,1,1]]}],
      "connections":[]}"#;
    assert!(Board::parse(disconnected)
        .unwrap_err()
        .0
        .starts_with("Disconnected board"));
}
