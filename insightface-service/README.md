# InsightFace sidecar

A small, **stateless** Python face-embedding service used by the Hikvision Attendance
app as a **second face-recognition layer** next to the cameras' own recognition.

The Spring Boot app sends captured photos here and gets back, per face, an **embedding**
(a 512-number vector) plus quality measurements (face size, head pose, sharpness). The Java
app keeps several reference photos per employee and does all matching, quality rules and
thresholds — this service only turns an image into embeddings and measurements.

It is used to:
- recognize faces the camera could not identify (fallback);
- double-check the camera's own matches (second opinion; disagreements go to review);
- check and embed employees' reference photos (Employees → Face photos);
- learn from captures a reviewer confirmed.

## How it fits in

```
Camera face event (captured JPEG)
        │
        ▼
Spring Boot  ──HTTP POST /embed──►  this service  ──►  {embedding[512], det_score, pose, blur, ...}
        │
        ▼
Java checks the face quality, compares it with every employee's photos and decides:
   Camera matched someone:   InsightFace agrees → record; clearly someone else → review
   Camera matched no one:    high & clear lead → record; uncertain/close call → review;
                             no match → unknown-capture library
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
  "image_size": [1280, 886],
  "faces": [
    { "embedding": [/* 512 L2-normalized floats */], "det_score": 0.93, "bbox": [x1,y1,x2,y2],
      "face_size": 148.0, "pose": [pitch, yaw, roll], "pose_source": "3d68", "blur": 6.3 }
  ]
}
```
An empty `faces` array means no face was detected.

- `face_size`: shorter side of the face box, in pixels.
- `pose`: head angles in degrees (yaw 0 = looking straight at the camera). `pose_source` is
  `3d68` when the model pack's 3D landmark model is available (buffalo_l has it), otherwise
  `kps` (a rough yaw estimate from the eyes and nose; pitch/roll unknown).
- `blur`: sharpness of the face, independent of brightness/contrast. Sharp faces are about
  3–11, slightly soft about 1, clearly blurred below 0.5.

The quality fields were added in v1.1 and are additive: the Java app also works with a v1.0
sidecar (it then skips the size/pose/sharpness checks).

## Quick test

```bash
curl -s http://127.0.0.1:8099/health
curl -s -X POST --data-binary @some_face.jpg \
     -H "Content-Type: image/jpeg" http://127.0.0.1:8099/embed
```
