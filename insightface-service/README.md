# InsightFace sidecar

A small, **stateless** Python face-embedding service used by the Hikvision Attendance
app as a **secondary (fallback) face-recognition layer**.

When the Hikvision camera cannot identify a face, the Spring Boot app sends the captured
photo here, gets back a face **embedding** (a 512-number vector), and compares it against
the embeddings of enrolled employees. All the matching logic and confidence thresholds
live in the Java app — this service only turns an image into an embedding.

## How it fits in

```
Camera can't recognize a face
        │  (captured JPEG)
        ▼
Spring Boot  ──HTTP POST /embed──►  this service  ──►  {embedding[512], det_score}
        │
        ▼
Java compares the embedding to enrolled employees (cosine similarity) and decides:
   High confidence  → record attendance
   Uncertain        → human review queue
   No match         → unknown-capture library (unchanged)
```

## Requirements

- Python 3.9–3.11
- See `requirements.txt`

## Setup

```bash
# Windows (PowerShell)
python -m venv .venv
.\.venv\Scripts\Activate.ps1
pip install -r requirements.txt

# Linux / macOS
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

> **First run downloads the model pack (`buffalo_l`, ~300 MB) into `~/.insightface/models`.
> This needs internet ONCE.** After that the service runs fully offline. On an air-gapped
> host, copy the `~/.insightface` folder from a machine that already downloaded it.

## Run

```bash
# Windows
.\run.ps1

# Linux / macOS
./run.sh

# or directly
uvicorn app:app --host 127.0.0.1 --port 8099
```

Keep it running alongside the Spring Boot app (same machine). Options for production:
- **Windows:** run as a service with [NSSM](https://nssm.cc/) pointing at `run.ps1`.
- **Linux:** a `systemd` unit that runs `run.sh`.

The Spring Boot side is pointed at it with `insightface.base-url=http://127.0.0.1:8099`
and only calls it when `insightface.enabled=true`. If this service is down, the app falls
back to its previous behavior (the face is saved as an unknown capture) — nothing breaks.

## Configuration (environment variables)

| Variable                | Default      | Meaning                                        |
|-------------------------|--------------|------------------------------------------------|
| `INSIGHTFACE_MODEL`     | `buffalo_l`  | InsightFace model pack name                    |
| `INSIGHTFACE_CTX_ID`    | `-1`         | `-1` = CPU, `0` = first GPU (needs onnxruntime-gpu) |
| `INSIGHTFACE_DET_SIZE`  | `640`        | Detector input size (larger = slower, better)  |

## Endpoints

### `GET /health`
```json
{ "status": "ok", "model": "buffalo_l" }
```

### `POST /embed`
Send a JPEG as the raw body (`Content-Type: image/jpeg`) **or** as JSON
`{"image_base64": "..."}`.

Response (faces ordered by `det_score`, most confident first):
```json
{
  "model": "buffalo_l",
  "faces": [
    { "embedding": [/* 512 L2-normalized floats */], "det_score": 0.93, "bbox": [x1,y1,x2,y2] }
  ]
}
```
An empty `faces` array means no face was detected.

## Quick test

```bash
curl -s http://127.0.0.1:8099/health
curl -s -X POST --data-binary @some_face.jpg \
     -H "Content-Type: image/jpeg" http://127.0.0.1:8099/embed
```
