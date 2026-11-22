#!/usr/bin/env python3
"""Genera tokens JWT EdDSA para perfilar el motor con SCHED_AUTH=jwt (bench/profile-server.sh).

Un token por cuerpo de students.json y alternatives.json, en el mismo orden (sub = student_id,
courses = eligible_courses). Lee la llave privada PKCS#8 que escribe `sched-simulation keygen`.
La salida son dos arreglos JSON de tokens: NO la versione (contiene credenciales válidas durante --ttl segundos).

  profile-tokens.py --key DIR/kid.key --kid kid --iss emisor --aud audiencia --out DIR [--ttl 900]
"""
import argparse, base64, json, time
from pathlib import Path
from cryptography.hazmat.primitives.serialization import load_pem_private_key


def b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--key", required=True)
    ap.add_argument("--kid", required=True)
    ap.add_argument("--iss", required=True)
    ap.add_argument("--aud", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--ttl", type=int, default=900)
    ap.add_argument("--data", default=str(Path(__file__).parent))
    a = ap.parse_args()
    key = load_pem_private_key(Path(a.key).read_bytes(), password=None)
    now = int(time.time())
    header = b64(json.dumps({"alg": "EdDSA", "typ": "JWT", "kid": a.kid}, separators=(",", ":")).encode())

    def token(body):
        claims = {"iss": a.iss, "aud": a.aud, "sub": body["student_id"], "courses": body["eligible_courses"],
                  "iat": now, "exp": now + a.ttl}
        signing_input = header + "." + b64(json.dumps(claims, separators=(",", ":")).encode())
        return signing_input + "." + b64(key.sign(signing_input.encode()))

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    for src, dst in (("students.json", "tokens-combos.json"), ("alternatives.json", "tokens-alts.json")):
        bodies = json.loads((Path(a.data) / src).read_text())
        (out / dst).write_text(json.dumps([token(b) for b in bodies]))
        print(f"{dst}: {len(bodies)} tokens")


if __name__ == "__main__":
    main()
