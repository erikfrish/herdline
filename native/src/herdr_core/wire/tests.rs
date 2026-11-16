use super::*;

#[test]
fn a_frame_reads_back_as_written() {
    let mut out = Vec::new();
    write(&mut out, 7, Kind::Credit, &[1, 2, 3, 4]).unwrap();
    assert_eq!(out.len(), HEADER + 4);
    let mut from = out.as_slice();
    let frame = read(&mut from).unwrap().unwrap();
    assert_eq!(
        frame,
        Frame {
            channel: 7,
            kind: Some(Kind::Credit),
            payload: vec![1, 2, 3, 4],
        }
    );
    assert!(read(&mut from).unwrap().is_none());
}

#[test]
fn an_unknown_kind_is_kept_as_none() {
    let mut out = Vec::new();
    write(&mut out, 1, Kind::Data, b"x").unwrap();
    out[4] = 99;
    let frame = read(&mut out.as_slice()).unwrap().unwrap();
    assert_eq!(frame.kind, None);
    assert_eq!(frame.payload, b"x");
}

#[test]
fn a_header_over_the_payload_limit_is_refused_before_reading_it() {
    let mut out = Vec::new();
    out.extend_from_slice(&1u32.to_le_bytes());
    out.push(0);
    out.extend_from_slice(&(MAX_PAYLOAD as u32 + 1).to_le_bytes());
    assert!(matches!(
        read(&mut out.as_slice()),
        Err(Error::CoreProtocol(_))
    ));
}

#[test]
fn a_stream_cut_inside_a_header_is_an_error_not_an_end() {
    let bytes = [1u8, 0, 0];
    assert!(read(&mut bytes.as_slice()).is_err());
}

#[test]
fn sizes_are_little_endian_cols_then_rows() {
    assert_eq!(size(300, 2), [44, 1, 2, 0]);
    assert_eq!(parse_size(&size(300, 2)), Some((300, 2)));
    assert_eq!(parse_size(&[1, 2, 3]), None);
}
