"""
InsightFace sidecar for the Hikvision Attendance app.

A tiny, STATELESS face-embedding service. It does face detection + embedding only;
the Spring Boot app owns the gallery of enrolled employees, the cosine matching and
the confidence thresholds. Keeping this service stateless means it can be restarted
freely without losing any state and the database stays the single source of truth.

Endpoints
---------
GET  /health              -> {"status": "ok", "model": "<name>"}
POST /embed               -> detect faces in a JPEG and return a normalized embedding
                             per face. Accepts either:
                               * raw image bytes  (Content-Type: image/jpeg / image/*
                                 or application/octet-stream), or
                               * JSON  {"image_base64": "<base64 jpeg>"}

Response of /embed:
    {
      "model": "buffalo_l",
      "image_size": [width, height],
      "faces": [
        {"embedding": [512 floats, L2-normalized], "det_score": 0.93,
         "bbox": [x1, y1, x2, y2],
         "face_size": 148.0,          # shorter side of the face box, in pixels
         "pose": [pitch, yaw, roll],  # degrees; yaw 0 = frontal (null if unknown)
         "pose_source": "3d68",       # "3d68" (landmark model) or "kps" (rough estimate)
         "blur": 182.4}               # Laplacian variance of the face resized to 112x112;
                                      # higher = sharper
      ]
    }
The quality fields are measurements only; the Spring Boot app decides what is acceptable.
"faces" is ordered by det_score descending, so faces[0] is the most confident face.

Run it
------
    pip install -r requirements.txt
    uvicorn app:app --host 127.0.0.1 --port 8099

The FIRST run downloads the model pack (needs internet once); afterwards it runs offline.
See README.md.
"""

import base64
import io
import os
from typing import Optional

import numpy as np
from fastapi import FastAPI, Request, HTTPException
from PIL import Image

# insightface is imported lazily inside _get_model() so that `GET /health` and import
# errors give a clear message instead of failing at module load.

MODEL_NAME = os.environ.get("INSIGHTFACE_MODEL", "buffalo_l")
# ctx_id = -1 -> CPU (onnxruntime). Set INSIGHTFACE_CTX_ID=0 to use the first GPU.
CTX_ID = int(os.environ.get("INSIGHTFACE_CTX_ID", "-1"))
# Detection size; larger = more accurate but slower.
DET_SIZE = int(os.environ.get("INSIGHTFACE_DET_SIZE", "640"))

app = FastAPI(title="InsightFace Attendance Sidecar", version="1.1.0")

_model = None


def _get_model():
    """Load the FaceAnalysis model once and cache it."""
    global _model
    if _model is None:
        from insightface.app import FaceAnalysis

        model = FaceAnalysis(name=MODEL_NAME)
        model.prepare(ctx_id=CTX_ID, det_size=(DET_SIZE, DET_SIZE))
        _model = model
    return _model


@app.on_event("startup")
def _warm_up():
    # Load the model at startup so the first /embed request is not slow and so a
    # missing model pack fails loudly on boot rather than on the first camera event.
    try:
        _get_model()
    except Exception as exc:  # noqa: BLE001
        # Do not crash the process; /health will still answer and /embed will report
        # the error. This makes it obvious in the logs what went wrong.
        print(f"[insightface] WARNING: model '{MODEL_NAME}' failed to load: {exc}")


@app.get("/health")
def health():
    return {"status": "ok", "model": MODEL_NAME}


def _read_image(raw: bytes) -> np.ndarray:
    """Decode JPEG/PNG bytes into a BGR numpy array (what insightface expects)."""
    try:
        img = Image.open(io.BytesIO(raw)).convert("RGB")
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=400, detail=f"Could not decode image: {exc}")
    rgb = np.asarray(img)
    # insightface/opencv use BGR order.
    return rgb[:, :, ::-1].copy()


async def _extract_bytes(request: Request) -> bytes:
    content_type = (request.headers.get("content-type") or "").lower()
    if "application/json" in content_type:
        payload = await request.json()
        b64 = payload.get("image_base64") if isinstance(payload, dict) else None
        if not b64:
            raise HTTPException(status_code=400, detail="Missing 'image_base64' field")
        try:
            return base64.b64decode(b64)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=400, detail=f"Invalid base64: {exc}")
    # Raw body (image/jpeg, image/*, application/octet-stream or anything else).
    body = await request.body()
    if not body:
        raise HTTPException(status_code=400, detail="Empty request body")
    return body


FACE_NORM_SIZE = 112


def _blur_score(img: np.ndarray, bbox) -> Optional[float]:
    """Sharpness of the face crop: variance of a 4-neighbour Laplacian on the grayscale face
    resized to 112x112 (so the value is comparable between small and large faces)."""
    if bbox is None:
        return None
    h, w = img.shape[:2]
    x1, y1, x2, y2 = [int(round(v)) for v in bbox]
    x1, y1 = max(0, x1), max(0, y1)
    x2, y2 = min(w, x2), min(h, y2)
    if x2 - x1 < 4 or y2 - y1 < 4:
        return None
    crop = img[y1:y2, x1:x2, ::-1]  # back to RGB for PIL
    gray = Image.fromarray(np.ascontiguousarray(crop)).convert("L").resize(
        (FACE_NORM_SIZE, FACE_NORM_SIZE), Image.BILINEAR)
    g = np.asarray(gray, dtype=np.float32)
    lap = (-4.0 * g[1:-1, 1:-1] + g[:-2, 1:-1] + g[2:, 1:-1] + g[1:-1, :-2] + g[1:-1, 2:])
    return float(lap.var())


def _pose(face):
    """(pitch, yaw, roll) in degrees and where it came from. Uses the 3D-68 landmark model's
    pose when the model pack provides it (buffalo_l does); otherwise a rough yaw estimate from
    the 5 detection keypoints (eyes + nose), with pitch/roll unknown."""
    pose = face.get("pose") if hasattr(face, "get") else None
    if pose is not None:
        p = [float(x) for x in np.asarray(pose).tolist()[:3]]
        return p, "3d68"
    kps = getattr(face, "kps", None)
    if kps is None or len(kps) < 3:
        return None, None
    left_eye, right_eye, nose = kps[0], kps[1], kps[2]
    span = float(right_eye[0] - left_eye[0])
    if abs(span) < 1e-3:
        return None, None
    ratio = (float(nose[0]) - float(left_eye[0])) / span  # ~0.5 when frontal
    yaw = max(-90.0, min(90.0, (ratio - 0.5) * 120.0))
    return [0.0, yaw, 0.0], "kps"


@app.post("/embed")
async def embed(request: Request):
    raw = await _extract_bytes(request)
    img = _read_image(raw)

    try:
        model = _get_model()
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=503, detail=f"Model not available: {exc}")

    faces = model.get(img)
    results = []
    for f in faces:
        emb = np.asarray(f.normed_embedding, dtype=np.float32)  # already L2-normalized
        bbox = [float(x) for x in f.bbox.tolist()] if f.bbox is not None else None
        face_size = min(bbox[2] - bbox[0], bbox[3] - bbox[1]) if bbox else None
        pose, pose_source = _pose(f)
        results.append(
            {
                "embedding": emb.tolist(),
                "det_score": float(f.det_score),
                "bbox": bbox,
                "face_size": float(face_size) if face_size is not None else None,
                "pose": pose,
                "pose_source": pose_source,
                "blur": _blur_score(img, bbox),
            }
        )

    results.sort(key=lambda r: r["det_score"], reverse=True)
    height, width = img.shape[:2]
    return {"model": MODEL_NAME, "image_size": [int(width), int(height)], "faces": results}
