"""Generate an HTML report showing OCR diffs for each sample.

Uses `arl diagnose` to run the pipeline and get per-sample results, then
produces a single HTML file with all samples sorted worst-first. Each sample
shows the image, CER score, and a character-level diff between expected and
actual text.

Usage:
    uv run diff-report --data ../../datasets/initial
"""

import argparse
import difflib
import json
from html import escape
from pathlib import Path

from booksnap_labs.tools.diagnose import run_diagnose


def _tokenize(text: str) -> list[str]:
    """Split text into words and newline tokens, preserving structure."""
    import re

    return [t for t in re.split(r"(\n| )", text) if t and t != " "]


def _word_diff_html(expected: str, actual: str) -> str:
    """Word-level diff with character-level detail inside changed words."""
    exp_words = _tokenize(expected)
    act_words = _tokenize(actual)
    sm = difflib.SequenceMatcher(None, exp_words, act_words)
    parts_expected = []
    parts_actual = []

    def _render(tokens: list[str]) -> str:
        """Join tokens: words separated by spaces, \\n tokens become newlines."""
        result = []
        for i, tok in enumerate(tokens):
            if tok == "\n":
                result.append("\n")
            else:
                if i > 0 and tokens[i - 1] != "\n":
                    result.append(" ")
                result.append(tok)
        return "".join(result)

    last_exp_tok = None
    last_act_tok = None

    def _pre(last_tok: str | None, first_tok: str) -> str:
        if last_tok is None:
            return ""
        if first_tok == "\n" or last_tok == "\n":
            return ""
        return " "

    for op, i1, i2, j1, j2 in sm.get_opcodes():
        exp_toks = exp_words[i1:i2]
        act_toks = act_words[j1:j2]

        if op == "equal":
            pre_e = _pre(last_exp_tok, exp_toks[0]) if exp_toks else ""
            pre_a = _pre(last_act_tok, act_toks[0]) if act_toks else ""
            text = escape(_render(exp_toks))
            parts_expected.append(f'{pre_e}<span class="eq">{text}</span>')
            parts_actual.append(f'{pre_a}<span class="eq">{text}</span>')
            last_exp_tok = exp_toks[-1] if exp_toks else last_exp_tok
            last_act_tok = act_toks[-1] if act_toks else last_act_tok
        elif op == "delete":
            pre = _pre(last_exp_tok, exp_toks[0]) if exp_toks else ""
            text = escape(_render(exp_toks))
            parts_expected.append(f'{pre}<span class="del">{text}</span>')
            last_exp_tok = exp_toks[-1] if exp_toks else last_exp_tok
        elif op == "insert":
            pre = _pre(last_act_tok, act_toks[0]) if act_toks else ""
            text = escape(_render(act_toks))
            parts_actual.append(f'{pre}<span class="ins">{text}</span>')
            last_act_tok = act_toks[-1] if act_toks else last_act_tok
        elif op == "replace":
            pre_e = _pre(last_exp_tok, exp_toks[0]) if exp_toks else ""
            pre_a = _pre(last_act_tok, act_toks[0]) if act_toks else ""
            exp_phrase = _render(exp_toks)
            act_phrase = _render(act_toks)
            csm = difflib.SequenceMatcher(None, exp_phrase, act_phrase)
            exp_sub = []
            act_sub = []
            for cop, ci1, ci2, cj1, cj2 in csm.get_opcodes():
                if cop == "equal":
                    text = escape(exp_phrase[ci1:ci2])
                    exp_sub.append(f'<span class="eq">{text}</span>')
                    act_sub.append(f'<span class="eq">{text}</span>')
                elif cop == "delete":
                    exp_sub.append(
                        f'<span class="del">{escape(exp_phrase[ci1:ci2])}</span>'
                    )
                elif cop == "insert":
                    act_sub.append(
                        f'<span class="ins">{escape(act_phrase[cj1:cj2])}</span>'
                    )
                elif cop == "replace":
                    exp_sub.append(
                        f'<span class="del">{escape(exp_phrase[ci1:ci2])}</span>'
                    )
                    act_sub.append(
                        f'<span class="ins">{escape(act_phrase[cj1:cj2])}</span>'
                    )
            parts_expected.append(f"{pre_e}{''.join(exp_sub)}")
            parts_actual.append(f"{pre_a}{''.join(act_sub)}")
            last_exp_tok = exp_toks[-1] if exp_toks else last_exp_tok
            last_act_tok = act_toks[-1] if act_toks else last_act_tok

    return (
        '<div class="diff-row">'
        '<div class="diff-col"><h4>Expected</h4><pre>'
        + "".join(parts_expected)
        + "</pre></div>"
        '<div class="diff-col"><h4>Actual</h4><pre>'
        + "".join(parts_actual)
        + "</pre></div></div>"
    )


def generate_report(
    data_dir: Path,
    diagnose_data: dict,
    output_path: Path,
    image_dir: Path | None = None,
    spell_check_disabled: bool = False,
) -> None:
    if image_dir is None:
        image_dir = data_dir
    gt = json.loads((data_dir / "ground_truth.json").read_text())
    gt_by_id = {img["id"]: img for img in gt["images"]}

    samples = []
    for s in diagnose_data["per_sample"]:
        gt_entry = gt_by_id.get(s["sample_id"], {})
        samples.append(
            {
                "id": s["sample_id"],
                "path": gt_entry.get("path", ""),
                "expected": s.get("expected", ""),
                "actual": s.get("got", ""),
                "cer": s.get("cer", s.get("score", 0)),
                "page_expected": s.get("page_number", {}).get("expected")
                if isinstance(s.get("page_number"), dict)
                else None,
                "page_actual": s.get("page_number", {}).get("got")
                if isinstance(s.get("page_number"), dict)
                else None,
                "latency_ms": s.get("latency_ms", 0),
                "language": gt_entry.get("language", "?"),
            }
        )

    samples.sort(key=lambda s: s["cer"], reverse=True)

    html_parts = [
        """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>BookSnap OCR Diff Report</title>
<style>
body { font-family: -apple-system, sans-serif; margin: 20px; background: #f5f5f5; }
h1 { color: #333; }
.summary { background: #fff; padding: 15px; border-radius: 8px; margin-bottom: 20px; }
.sample { background: #fff; padding: 20px; border-radius: 8px; margin-bottom: 20px;
          box-shadow: 0 1px 3px rgba(0,0,0,0.1); }
.sample-header { display: flex; justify-content: space-between; align-items: center;
                 margin-bottom: 15px; }
.sample-header h3 { margin: 0; }
.cer-badge { padding: 4px 12px; border-radius: 12px; font-weight: bold; color: white; }
.cer-good { background: #4caf50; }
.cer-ok { background: #ff9800; }
.cer-bad { background: #f44336; }
.sample-body { display: flex; gap: 20px; }
.sample-image { flex: 0 0 300px; }
.sample-image img { max-width: 300px; max-height: 500px; border-radius: 4px; }
.sample-diff { flex: 1; min-width: 0; }
.diff-row { display: flex; gap: 15px; }
.diff-col { flex: 1; min-width: 0; }
.diff-col h4 { margin: 0 0 5px 0; color: #666; font-size: 12px; }
.diff-col pre { white-space: pre-wrap; word-break: break-word; font-size: 13px;
                line-height: 1.5; background: #fafafa; padding: 10px; border-radius: 4px; }
.eq { color: #333; }
.del { background: #ffcdd2; color: #b71c1c; text-decoration: line-through; }
.ins { background: #fff3e0; color: #e65100; }
.meta { color: #888; font-size: 13px; margin-bottom: 10px; }
</style></head><body>
<h1>BookSnap OCR Diff Report</h1>
""",
    ]

    agg_score = diagnose_data.get("score", 0)
    agg_metrics = diagnose_data.get("metrics", {})
    avg_cer = sum(s["cer"] for s in samples) / len(samples) if samples else 0
    html_parts.append(
        f'<div class="summary">'
        f"<strong>{len(samples)}</strong> samples &middot; "
        f"Composite score: <strong>{agg_score:.4f}</strong> &middot; "
        f"Mean CER: <strong>{avg_cer:.4f}</strong> &middot; "
        f"WER: <strong>{agg_metrics.get('wer', 0):.4f}</strong> &middot; "
        f"Worst: <strong>{samples[0]['cer']:.4f}</strong> &middot; "
        f"Best: <strong>{samples[-1]['cer']:.4f}</strong>"
        f"</div>"
    )

    if spell_check_disabled:
        html_parts.append(
            '<div style="background:#fff3e0;padding:8px 12px;border-radius:4px;'
            'margin-bottom:16px;color:#e65100;font-size:14px;">'
            "Spell checking was <strong>disabled</strong> for this report."
            "</div>"
        )

    for s in samples:
        cer_class = (
            "cer-good"
            if s["cer"] < 0.03
            else "cer-ok"
            if s["cer"] < 0.08
            else "cer-bad"
        )
        image_path = image_dir / s["path"]
        img_src = str(image_path) if s["path"] and image_path.exists() else ""

        page_info = ""
        if s["page_expected"] is not None:
            match = "✓" if s["page_actual"] == s["page_expected"] else "✗"
            page_info = f" &middot; Page: {s['page_actual']} (expected {s['page_expected']}) {match}"

        diff_html = _word_diff_html(s["expected"], s["actual"])

        html_parts.append(
            f'<div class="sample">'
            f'<div class="sample-header">'
            f"<h3>{escape(s['id'])}</h3>"
            f'<span class="cer-badge {cer_class}">CER {s["cer"]:.4f}</span>'
            f"</div>"
            f'<div class="meta">'
            f"Language: {s['language']} &middot; Latency: {s['latency_ms']:.0f}ms{page_info}"
            f"</div>"
            f'<div class="sample-body">'
        )

        if img_src:
            html_parts.append(
                f'<div class="sample-image"><a href="{img_src}">'
                f'<img src="{img_src}" loading="lazy"></a></div>'
            )

        html_parts.append(f'<div class="sample-diff">{diff_html}</div></div></div>')

    html_parts.append("</body></html>")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("".join(html_parts))


def draw_bounds_from_diagnose(
    data_dir: Path, diagnose_data: dict, output_dir: Path,
) -> Path:
    """Draw bounding boxes using pre-computed diagnose data. Returns output dir."""
    from booksnap_labs.tools.visualize_bounds import draw_bounds

    gt = json.loads((data_dir / "ground_truth.json").read_text())
    samples_by_id = {s["sample_id"]: s for s in diagnose_data.get("per_sample", [])}

    for img_entry in gt["images"]:
        image_path = data_dir / img_entry["path"]
        if not image_path.exists():
            continue
        sample = samples_by_id.get(img_entry["id"], {})
        draw_bounds(image_path, sample, output_dir / img_entry["path"])

    return output_dir


def main():
    parser = argparse.ArgumentParser(description="Generate OCR diff report")
    parser.add_argument("--data", required=True, help="Path to dataset directory")
    parser.add_argument("--output", default="viz/report.html", help="Output HTML file")
    parser.add_argument(
        "--bounds",
        action="store_true",
        help="Run bounds visualization first and show annotated images in the report",
    )
    parser.add_argument(
        "--no-spell-check",
        action="store_true",
        help="Disable spell checking in the pipeline",
    )
    args = parser.parse_args()

    data_dir = Path(args.data).resolve()
    output_path = Path(args.output).resolve()

    env_extra = {}
    if args.no_spell_check:
        env_extra["BOOKSNAP_SPELL_CHECK"] = "false"

    print(f"Running arl diagnose on {data_dir}...")
    diagnose_data = run_diagnose(data_dir, env_extra=env_extra)

    image_dir = data_dir
    if args.bounds:
        print("Drawing bounding boxes...")
        image_dir = draw_bounds_from_diagnose(data_dir, diagnose_data, output_path.parent)

    print("Generating report...")
    generate_report(
        data_dir, diagnose_data, output_path,
        image_dir=image_dir,
        spell_check_disabled=args.no_spell_check,
    )
    print(f"Report saved to {output_path}")


if __name__ == "__main__":
    main()
