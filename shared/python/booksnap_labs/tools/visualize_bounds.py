"""Visualize bounding boxes on dataset images.

Runs `arl diagnose` to get per-sample results including bounds, then draws
text_bounds (green) and page_number_bounds (orange) on each image.

Usage:
    visualize-bounds --data ../../datasets/initial --output viz/
"""

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw

from booksnap_labs.tools.diagnose import run_diagnose


def draw_bounds(image_path: Path, sample: dict, output_path: Path) -> None:
    img = Image.open(image_path)
    draw = ImageDraw.Draw(img)

    border = 10

    tb = sample.get("text_bounds")
    if tb and tb.get("width", 0) > 0:
        x, y, w, h = tb["x"], tb["y"], tb["width"], tb["height"]
        draw.rectangle(
            [x + border, y + border, x + w - border, y + h - border],
            outline="green",
            width=border,
        )

    pnb = sample.get("page_number_bounds")
    if pnb and pnb.get("width", 0) > 0:
        x, y, w, h = pnb["x"], pnb["y"], pnb["width"], pnb["height"]
        draw.rectangle(
            [x + border, y + border, x + w - border, y + h - border],
            outline="#FF6600",
            width=border,
        )
        page_info = sample.get("page_number")
        page_num = page_info.get("got", "?") if isinstance(page_info, dict) else "?"
        draw.text((x + border, max(0, y - 15)), f"page {page_num}", fill="#FF6600")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    img.save(output_path)


def main():
    parser = argparse.ArgumentParser(
        description="Visualize bounding boxes on dataset images"
    )
    parser.add_argument("--data", required=True, help="Path to dataset directory")
    parser.add_argument(
        "--output", default="viz", help="Output directory for annotated images"
    )
    args = parser.parse_args()

    data_dir = Path(args.data).resolve()
    output_dir = Path(args.output).resolve()

    print(f"Running pipeline on {data_dir}...")
    diagnose_data = run_diagnose(data_dir)

    gt = json.loads((data_dir / "ground_truth.json").read_text())
    samples_by_id = {s["sample_id"]: s for s in diagnose_data.get("per_sample", [])}

    count = 0
    for img_entry in gt["images"]:
        image_id = img_entry["id"]
        image_path = data_dir / img_entry["path"]

        if not image_path.exists():
            print(f"  Skipping {image_id}: image not found", file=sys.stderr)
            continue

        sample = samples_by_id.get(image_id, {})
        output_path = output_dir / img_entry["path"]

        draw_bounds(image_path, sample, output_path)
        count += 1

        tb = sample.get("text_bounds")
        pnb = sample.get("page_number_bounds")
        print(
            f"  {image_id}: text_bounds={'yes' if tb else 'no'}, "
            f"page_number_bounds={'yes' if pnb else 'no'}"
        )

    print(f"\nSaved {count} annotated images to {output_dir}")


if __name__ == "__main__":
    main()
