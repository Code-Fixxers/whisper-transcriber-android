#!/usr/bin/env python3
"""Probe a live WhisperLiveKit server with a known WAV file.

This script uses only the Python standard library so it can run on a clean
runner or developer machine. It verifies:
  - /health
  - /v1/audio/transcriptions
  - /asr WebSocket config and final text
"""

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import time
import urllib.parse
import urllib.request
import uuid
import wave


def http_get_json(url, timeout):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def rest_transcribe(base_url, wav_path, timeout):
    boundary = "----whisperlivekit-" + uuid.uuid4().hex
    with open(wav_path, "rb") as wav_file:
        wav_bytes = wav_file.read()

    parts = [
        (
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="file"; filename="probe.wav"\r\n'
            "Content-Type: audio/wav\r\n\r\n"
        ).encode("utf-8"),
        wav_bytes,
        (
            f"\r\n--{boundary}\r\n"
            'Content-Disposition: form-data; name="model"\r\n\r\n'
            "whisper-1\r\n"
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="language"\r\n\r\n'
            "en\r\n"
            f"--{boundary}\r\n"
            'Content-Disposition: form-data; name="response_format"\r\n\r\n'
            "json\r\n"
            f"--{boundary}--\r\n"
        ).encode("utf-8"),
    ]
    body = b"".join(parts)

    request = urllib.request.Request(
        base_url.rstrip("/") + "/v1/audio/transcriptions",
        data=body,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def read_exact(sock, count):
    data = b""
    while len(data) < count:
        chunk = sock.recv(count - len(data))
        if not chunk:
            raise EOFError("socket closed")
        data += chunk
    return data


def read_frame(sock):
    b1, b2 = read_exact(sock, 2)
    opcode = b1 & 0x0F
    length = b2 & 0x7F
    masked = bool(b2 & 0x80)
    if length == 126:
        length = struct.unpack("!H", read_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack("!Q", read_exact(sock, 8))[0]
    mask = read_exact(sock, 4) if masked else b""
    payload = read_exact(sock, length) if length else b""
    if masked:
        payload = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    return opcode, payload


def send_frame(sock, opcode, payload):
    mask = os.urandom(4)
    first_byte = 0x80 | opcode
    length = len(payload)
    if length < 126:
        header = struct.pack("!BB", first_byte, 0x80 | length)
    elif length < 65536:
        header = struct.pack("!BBH", first_byte, 0x80 | 126, length)
    else:
        header = struct.pack("!BBQ", first_byte, 0x80 | 127, length)
    masked = bytes(value ^ mask[index % 4] for index, value in enumerate(payload))
    sock.sendall(header + mask + masked)


def open_websocket(base_url, timeout):
    parsed = urllib.parse.urlparse(base_url)
    scheme = parsed.scheme or "http"
    host = parsed.hostname or parsed.path
    port = parsed.port or (443 if scheme == "https" else 80)
    path = "/asr?language=en&mode=full"

    raw = socket.create_connection((host, port), timeout=timeout)
    sock = ssl.create_default_context().wrap_socket(raw, server_hostname=host) if scheme == "https" else raw
    key = base64.b64encode(os.urandom(16)).decode("ascii")
    request = (
        f"GET {path} HTTP/1.1\r\n"
        f"Host: {host}:{port}\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        f"Sec-WebSocket-Key: {key}\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n"
    )
    sock.sendall(request.encode("ascii"))
    response = b""
    while b"\r\n\r\n" not in response:
        response += sock.recv(4096)
    if b" 101 " not in response.split(b"\r\n", 1)[0]:
        raise RuntimeError(response.decode("iso-8859-1", "replace"))
    sock.settimeout(timeout)
    return sock


def read_wav(wav_path):
    with wave.open(wav_path, "rb") as wav:
        channels = wav.getnchannels()
        sample_rate = wav.getframerate()
        sample_width = wav.getsampwidth()
        frames = wav.getnframes()
        pcm = wav.readframes(frames)
    if channels != 1 or sample_rate != 16000 or sample_width != 2:
        raise ValueError(
            f"WebSocket PCM probe requires 16 kHz mono s16le WAV; got "
            f"channels={channels} sample_rate={sample_rate} sample_width={sample_width}"
        )
    return pcm


def collect_text(message):
    parts = []
    for line in message.get("lines") or []:
        text = line.get("text")
        if text:
            parts.append(text)
    for key in ("buffer_transcription", "buffer_diarization", "buffer_translation"):
        text = message.get(key)
        if text:
            parts.append(text)
    return " ".join(parts).strip()


def websocket_transcribe(base_url, wav_path, timeout):
    pcm = read_wav(wav_path)
    with open(wav_path, "rb") as wav_file:
        wav_bytes = wav_file.read()

    sock = open_websocket(base_url, timeout)
    config_opcode, config_payload = read_frame(sock)
    if config_opcode != 0x1:
        raise RuntimeError(f"expected text config frame, got opcode={config_opcode}")
    config = json.loads(config_payload.decode("utf-8"))
    use_pcm = bool(config.get("useAudioWorklet"))
    audio = pcm if use_pcm else wav_bytes

    for offset in range(0, len(audio), 16000):
        send_frame(sock, 0x2, audio[offset : offset + 16000])
        time.sleep(0.25)
    send_frame(sock, 0x2, b"")

    latest_text = ""
    messages = []
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        opcode, payload = read_frame(sock)
        if opcode == 0x8:
            break
        if opcode != 0x1:
            continue
        message = json.loads(payload.decode("utf-8"))
        messages.append(message)
        if message.get("type") == "ready_to_stop":
            break
        text = collect_text(message)
        if text:
            latest_text = text
    sock.close()
    return {
        "config": config,
        "sentMode": "pcm" if use_pcm else "encoded-wav",
        "audioSha256": hashlib.sha256(audio).hexdigest(),
        "messageCount": len(messages) + 1,
        "text": latest_text,
        "lastMessage": messages[-1] if messages else None,
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=os.environ.get("WHISPERLIVEKIT_URL", "http://100.101.157.56:8090"))
    parser.add_argument("--wav", required=True)
    parser.add_argument("--expect", default="")
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()

    summary = {
        "url": args.url,
        "health": http_get_json(args.url.rstrip("/") + "/health", args.timeout),
        "rest": rest_transcribe(args.url, args.wav, args.timeout),
        "websocket": websocket_transcribe(args.url, args.wav, args.timeout),
    }
    print(json.dumps(summary, indent=2, sort_keys=True))

    expected = args.expect.lower().strip()
    if expected:
        combined = f"{summary['rest'].get('text', '')} {summary['websocket'].get('text', '')}".lower()
        if expected not in combined:
            print(f"expected text fragment not found: {args.expect!r}", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
