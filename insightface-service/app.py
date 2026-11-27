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
      "faces": [
        {"embedding": [512 floats, L2-normalized], "det_score": 0.93,
         "bbox": [x1, y1, x2, y2]}
      ]
    }
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

app = FastAPI(title="InsightFace Attendance Sidecar", version="1.0.0")

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
        results.append(
            {
                "embedding": emb.tolist(),
                "det_score": float(f.det_score),
                "bbox": bbox,
            }
        )

    results.sort(key=lambda r: r["det_score"], reverse=True)
    return {"model": MODEL_NAME, "faces": results}
