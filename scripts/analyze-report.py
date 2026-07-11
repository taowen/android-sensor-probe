#!/usr/bin/env python3
"""Summarize a Sensor Probe community ZIP."""
import argparse, collections, json, struct, zipfile, statistics

HEADER = struct.Struct("<IHBBQQhhiII")
KINDS={11:"int-submit",12:"int-complete",21:"bulk-submit",22:"bulk-complete",31:"ctrl-submit",32:"ctrl-complete",41:"async-submit",42:"async-callback",43:"async-resubmit",44:"async-cancel",51:"claim",52:"release"}

def main():
    ap=argparse.ArgumentParser();ap.add_argument("report");args=ap.parse_args()
    with zipfile.ZipFile(args.report) as z:
        report=json.loads(z.read("report.json"));device=json.loads(z.read("device.json"))
        print(f"{device.get('brand')} {device.get('identified_model')}  VID:PID={device['vid']:04x}:{device['pid']:04x}")
        print(f"phone={device['phone']['manufacturer']} {device['phone']['model']} Android {device['phone']['android']}")
        print(f"decoded={report['decoded_readings']} rejected={report['decode_failures']}")
        counts=collections.Counter();lengths=collections.Counter();errors=collections.Counter();payload_bytes=0
        pending={};durations=collections.defaultdict(list)
        with z.open("raw/usb-transfers.bin") as f:
            while True:
                h=f.read(HEADER.size)
                if not h:break
                if len(h)!=HEADER.size:raise ValueError("truncated trace header")
                magic,version,kind,direction,host_ns,seq,intf,ep,status,requested,actual=HEADER.unpack(h)
                if magic!=0x52545053 or version!=1:raise ValueError(f"bad trace record at {seq}")
                payload=f.read(actual)
                if len(payload)!=actual:raise ValueError("truncated payload")
                key=(intf,f"0x{ep&255:02x}","IN" if direction else "OUT",kind)
                counts[key]+=1;lengths[(key,actual)]+=1;payload_bytes+=actual
                if status:errors[(key,status)]+=1
                transfer=(intf,ep,direction)
                if kind in (11,21,31,41,43):pending[transfer]=host_ns
                elif kind in (12,22,32,42) and transfer in pending:
                    submitted=pending.pop(transfer)
                    if status==0:durations[(transfer,kind)].append((host_ns-submitted)/1e6)
        print(f"native records={sum(counts.values())} payload={payload_bytes} bytes")
        for key,n in counts.most_common():
            distribution=", ".join(f"{length}B×{count}" for (k,length),count in lengths.items() if k==key)
            print(f"  if={key[0]} ep={key[1]} {key[2]} {KINDS.get(key[3],f'kind-{key[3]}')}: {n} [{distribution}]")
        if durations:
            print("host transfer durations (submit -> complete/callback):")
            for (transfer,kind),values in durations.items():
                values.sort();p95=values[min(len(values)-1,int(len(values)*.95))]
                print(f"  if={transfer[0]} ep=0x{transfer[1]&255:02x} {KINDS.get(kind)} n={len(values)} median={statistics.median(values):.3f}ms p95={p95:.3f}ms max={max(values):.3f}ms")
        if errors:
            print("non-success transfer statuses:")
            for (key,status),n in errors.items():print(f"  {key} status={status}: {n}")

if __name__=="__main__":main()
