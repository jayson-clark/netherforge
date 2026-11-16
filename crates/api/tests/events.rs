use netherforge_api::events;

#[test]
fn a_class_hears_its_own_events_and_those_it_inherits() {
    let tick = events::of("nf", "tick").expect("nf has tick");
    assert_eq!(tick.owner, "nf");
    assert_eq!(tick.options, ["every"]);
    // A Mob's `death` is the one Living declares.
    let death = events::of("Mob", "death").expect("a mob dies");
    assert_eq!(death.owner, "Living");
    assert!(events::of("Mob", "no_such_event").is_none());
    assert!(events::names("Mob").contains(&"death"));
    assert!(events::is_custom("shop:bought"));
    assert!(!events::is_custom("tick"));
}

#[test]
fn every_event_is_one_its_owner_lists() {
    for event in events::EVENTS {
        assert_eq!(
            events::of(event.owner, event.name),
            Some(event),
            "{} {}",
            event.owner,
            event.name
        );
    }
}
