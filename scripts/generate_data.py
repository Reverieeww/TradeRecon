"""Hand-authored golden scenarios; answers never call the reconciliation implementation."""
import argparse
import csv
import hashlib
import io
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "data" / "demo"
FIELDS = ["event_id", "execution_id", "revision", "event_type", "account_id",
          "instrument_id", "side", "currency", "quantity", "price", "trade_date",
          "occurred_at", "note"]
BASE = dict(zip(FIELDS, ["ev001", "E100", 1, "NEW", "ACC01", "STOCK01", "BUY",
                        "USD", "50.000000", "100.250000", "2026-10-15",
                        "2026-10-15T09:00:00Z", "synthetic"]))
def event(**changes):
    return BASE | changes

def batch(source, rows, complete=False):
    return {"source": source, "business_date": "2026-10-15",
            "feed_complete": complete, "rows": rows}

def pair(left=None, right=None, complete=False):
    return [batch("INTERNAL", left or [event()], complete),
            batch("BROKER", right or [event()], complete)]

# Status values below are specifications for days 2/3, NOT outputs from today's engine.
CASES = [
 ("01_exact_match", pair(), {"E100": "MATCHED"}, "Both sources describe the same execution."),
 ("02_two_partial_fills", pair([event(),event(event_id="ev002",execution_id="E101")],
                               [event(),event(event_id="ev002",execution_id="E101")]),
  {"E100":"MATCHED","E101":"MATCHED"}, "Two 50-share executions of an order are two keys."),
 ("03_duplicate_delivery", pair([event(),event()]), {"E100":"MATCHED"}, "Second identical row is DUPLICATE; one event per source."),
 ("04_identity_conflict", pair([event(),event(price="101")]), {"E100":"WAITING"},
  "Reject whole INTERNAL batch: IDENTITY_CONFLICT. BROKER accepted; no internal events."),
 ("05_semantic_duplicate", pair([event(),event(event_id="ev002",note="another notification")]),
  {"E100":"MATCHED"}, "Different event ID, same execution revision and business fields: DUPLICATE."),
 ("06_multiple_differences", pair(right=[event(quantity="49",price="101",currency="GBP")]),
  {"E100":"BREAK"}, "List quantity, price and currency, not just the first difference."),
 ("07_missing_9m59", [batch("INTERNAL",[event()])], {"E100":"WAITING"}, "as_of = 09:09:59Z."),
 ("08_missing_exactly_10m", [batch("INTERNAL",[event()])], {"E100":"BREAK"}, "as_of = 09:10:00Z; boundary is inclusive."),
 ("09_missing_10m01", [batch("INTERNAL",[event()])], {"E100":"BREAK"}, "as_of = 09:10:01Z."),
 ("10_both_feeds_complete", [batch("INTERNAL",[event()],True),batch("BROKER",[],True)],
  {"E100":"BREAK"}, "Two complete declarations make the missing side a break immediately."),
 ("11_revision_gap", pair([event(),event(event_id="ev003",revision=3,event_type="CORRECT")]),
  {"E100":"DATA_BLOCKED"}, "Revision 2 is absent; do not take maximum revision."),
 ("12_gap_repaired", pair([event(event_id="ev003",revision=3,event_type="CORRECT"),
                           event(),event(event_id="ev002",revision=2,event_type="CORRECT")]),
  {"E100":"MATCHED"}, "All revisions present, shuffled order; project 1 then 2 then 3."),
 ("13_missing_new", pair([event(event_id="ev002",revision=2,event_type="CORRECT")]),
  {"E100":"DATA_BLOCKED"}, "Revision 1 NEW is missing."),
 ("14_one_side_cancel", pair([event(),event(event_id="ev002",revision=2,event_type="CANCEL")]),
  {"E100":"BREAK"}, "One source cancels, one remains active."),
 ("15_both_cancel", pair([event(),event(event_id="ev002",revision=2,event_type="CANCEL")],
                         [event(),event(event_id="ev002",revision=2,event_type="CANCEL")]),
  {"E100":"MATCHED"}, "reason_code MATCHED_CANCELLED; retain both histories."),
 ("16_after_cancel", pair([event(),event(event_id="ev002",revision=2,event_type="CANCEL"),
                           event(event_id="ev003",revision=3,event_type="CORRECT")]),
  {"E100":"DATA_BLOCKED"}, "No resurrection after CANCEL."),
 ("17_decimal_scale", pair(right=[event(quantity="50",price="100.25")]),
  {"E100":"MATCHED"}, "Numeric equality ignores representational scale."),
 ("18_excess_precision", pair([event(price="100.2500001")]), {"E100":"WAITING"},
  "Reject INTERNAL batch: validation error on price; BROKER accepted."),
 ("19_timezones", pair(right=[event(occurred_at="2026-10-15T10:00:00+01:00")]),
  {"E100":"MATCHED"}, "Convert to UTC; occurred_at is not the cross-source key."),
 ("20_quoted_utf8", pair(right=[event(note='synthetic, "quoted"\n中文说明')]),
  {"E100":"MATCHED"}, "A CSV parser must handle quoted comma, quote, newline and UTF-8.")
]

def outputs():
    result = {}
    manifest = {"synthetic": True, "seed": 101, "randomness": "none; 20 hand-authored cases",
                "business_date":"2026-10-15", "first_received_at":"2026-10-15T09:00:00Z",
                "note":"Fresh dataset per scenario. These are expected answers, not tested engine results.",
                "cases":[]}
    for name, batches, expected, why in CASES:
        entries = []
        for i, b in enumerate(batches, 1):
            stream = io.StringIO(newline="")
            writer = csv.DictWriter(stream, fieldnames=FIELDS, lineterminator="\n")
            writer.writeheader()
            writer.writerows(b["rows"])
            data = stream.getvalue().encode("utf-8")
            path = f"{name}/{i:02d}_{b['source'].lower()}.csv"
            result[path] = data
            entries.append({k:v for k,v in b.items() if k != "rows"} |
                           {"file":path,"row_count":len(b["rows"]),"sha256":hashlib.sha256(data).hexdigest()})
        as_of = {"07_missing_9m59":"09:09:59", "08_missing_exactly_10m":"09:10:00",
                 "09_missing_10m01":"09:10:01"}.get(name,"09:01:00")
        manifest["cases"].append({"id":name,"as_of":f"2026-10-15T{as_of}Z",
                                  "batches":entries,"expected_status_by_execution":expected,"explanation":why})
    result["manifest.json"] = (json.dumps(manifest,ensure_ascii=False,indent=2)+"\n").encode("utf-8")
    return result

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--check",action="store_true")
    args=parser.parse_args()
    generated=outputs()
    assert len(CASES)==20 and len({c[0] for c in CASES})==20
    for path, data in generated.items():
        destination=ROOT/path
        if args.check:
            assert destination.read_bytes()==data, f"Fixture drift: {path}"
        else:
            destination.parent.mkdir(parents=True,exist_ok=True)
            destination.write_bytes(data)
        if path.endswith(".csv"):
            rows=list(csv.DictReader(io.StringIO(data.decode("utf-8"))))
            assert all(set(row)==set(FIELDS) and None not in row.values() for row in rows)
    print(f"PASS: 20 golden scenarios, {len(generated)-1} CSV files; byte stability and CSV round-trip verified.")
    print("Matching/import expected outcomes have NOT been executed; implementations belong to days 2/3.")
if __name__=="__main__":
    main()

