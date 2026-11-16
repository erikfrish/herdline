    std::fs::write(&file, "{\"type\":\"session\"").unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Unconfined);
    let slot = "{\"type\":\"title\",\"v\":1,\"title\":\"\",\"updatedAt\":\"2026-11-16\",\"pad\":\" \"}\n";
    std::fs::write(&file, format!("{slot}{{\"type\":\"session\",\"id\":\"abc-1\"}}\n")).unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Confirmed);
    std::fs::write(&file, format!("{slot}{slot}")).unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Mismatch);
    std::fs::write(&file, format!("{slot}{{\"type\":\"session\",\"id\":\"other\"}}\n")).unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Mismatch);