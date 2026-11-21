"""Trusted resource decoder, not a generated-code runner. One private job directory per call."""
import hashlib
import json
import sys
import warnings
from io import BytesIO
from pathlib import Path
from PIL import Image, ImageOps, ImageFont, ImageDraw

MAX_BYTES = 32 * 1024 * 1024
MAX_PIXELS = 16_000_000
Image.MAX_IMAGE_PIXELS = MAX_PIXELS
warnings.simplefilter("error", Image.DecompressionBombWarning)


def inspect(job, kind="image"):
    if kind not in {"image", "font"}:
        raise ValueError("RESOURCE_KIND_INVALID")
    root = Path(job)
    if not root.is_absolute() or root.is_symlink() or not root.is_dir():
        raise ValueError("RESOURCE_JOB_INVALID")
    root = root.resolve(strict=True)
    source = root / "input.bin"
    if source.is_symlink() or not source.is_file() or source.resolve().parent != root:
        raise ValueError("RESOURCE_INPUT_INVALID")
    if not 0 < source.stat().st_size <= MAX_BYTES:
        raise ValueError("RESOURCE_INPUT_LIMIT")
    output = root / "preview.png"
    receipt = root / "inspection.json"
    if output.exists() or output.is_symlink() or receipt.exists() or receipt.is_symlink():
        raise ValueError("RESOURCE_OUTPUT_EXISTS")
    if kind == "font":
        font = ImageFont.truetype(BytesIO(source.read_bytes()), size=56)
        family, style = font.getname()
        sample = "AID 3D-HUB\nAa Bb Cc 0123456789\nScene Light Camera"
        preview = Image.new("RGBA", (1000, 320), "white")
        ImageDraw.Draw(preview).multiline_text((28, 22), sample, font=font, fill="black", spacing=20)
        metadata = {"format": "FONT", "fontFamily": str(family)[:256], "fontStyle": str(style)[:256],
                    "sampleText": sample, "previewWidth": preview.width, "previewHeight": preview.height,
                    "glyphCoverageVerified": False}
        with output.open("xb") as stream:
            preview.save(stream, format="PNG")
    else:
        with Image.open(source) as image:
            if image.format not in {"PNG", "JPEG", "WEBP", "GIF", "BMP", "TIFF", "PSD"}:
                raise ValueError("RESOURCE_IMAGE_FORMAT")
            width, height = image.size
            if width < 1 or height < 1 or width * height > MAX_PIXELS:
                raise ValueError("RESOURCE_IMAGE_PIXELS")
            # PSD opens at its merged composite; seek(0) is not a valid layer index in Pillow.
            if image.format in {"GIF", "TIFF", "WEBP"}:
                image.seek(0)
            layers = getattr(image, "layers", []) if image.format == "PSD" else []
            names = [str(layer[0])[:256] for layer in layers[:128]]
            metadata = {"format": image.format, "width": width, "height": height,
                        "mode": image.mode, "previewFrame": 0,
                        "mergedComposite": image.format == "PSD",
                        "animated": bool(getattr(image, "is_animated", False)),
                        "layerNames": names, "layerCount": len(layers),
                        "layersTruncated": len(layers) > len(names)}
            preview = ImageOps.exif_transpose(image).convert("RGBA")
            preview.thumbnail((2048, 2048), Image.Resampling.LANCZOS)
            preview.info.clear()
            with output.open("xb") as stream:
                preview.save(stream, format="PNG", optimize=False)
            metadata.update({"previewWidth": preview.width, "previewHeight": preview.height})
    if output.stat().st_size > 16 * 1024 * 1024:
        raise ValueError("RESOURCE_PREVIEW_LIMIT")
    with source.open("rb") as stream:
        source_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    with output.open("rb") as stream:
        preview_digest = hashlib.file_digest(stream, "sha256").hexdigest()
    metadata.update({"sourceSha256": source_digest,
                     "previewSha256": preview_digest,
                     "sourceBytes": source.stat().st_size, "previewBytes": output.stat().st_size,
                     "previewFile": "preview.png", "colourConversion": "display RGBA"})
    with receipt.open("x", encoding="utf-8") as stream:
        json.dump(metadata, stream, ensure_ascii=False)
    return metadata


if __name__ == "__main__":
    try:
        if len(sys.argv) not in {2, 3}:
            raise ValueError("RESOURCE_JOB_REQUIRED")
        print(json.dumps(inspect(sys.argv[1], sys.argv[2] if len(sys.argv) == 3 else "image"), ensure_ascii=False))
    except Exception as error:
        # Raw decoder exceptions can include user paths or binary content.
        print(json.dumps({"error": str(error) if isinstance(error, ValueError) and str(error).startswith("RESOURCE_") else "RESOURCE_IMAGE_DECODE_FAILED"}))
        sys.exit(1)
