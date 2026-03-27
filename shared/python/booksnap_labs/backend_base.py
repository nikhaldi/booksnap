"""Shared evaluation logic for booksnap backends.

Both the Android and iOS backends follow the same flow:
  1. Build the test app
  2. Load ground truth and filter samples
  3. Push files to the device/simulator
  4. Run tests
  5. Pull results
  6. Parse results and compute metrics

This module extracts steps 2 and 6 which are identical across platforms.
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

import jiwer
from autoresearch_lab.harness.backend import EvalBackend, EvalResult, SampleResult

_HOST_SERVICE_URL_ENV = "ARL_HOST_SERVICE_URL"

# Composite score weights (must sum to 1.0).
_WEIGHT_BODY_CER = 0.98
_WEIGHT_PAGE_NUMBER = 0.02


def compute_cer(reference: str, hypothesis: str) -> float:
    """Compute Character Error Rate, handling empty strings."""
    if not reference:
        return 0.0 if not hypothesis else 1.0
    return jiwer.cer(reference, hypothesis)


class BookSnapBackend(EvalBackend):
    """Base backend for booksnap labs. Subclasses set `build_action`."""

    build_action: str = "build"

    def __init__(self) -> None:
        self._daemon_url = os.environ.get(_HOST_SERVICE_URL_ENV)
        if not self._daemon_url:
            raise RuntimeError(
                f"{_HOST_SERVICE_URL_ENV} not set. "
                "This backend must run inside the sandbox container. "
                "Use 'arl run' to launch a sandboxed session."
            )

    def _daemon_call(self, action: str, **kwargs) -> dict:
        """Call the host-side daemon."""
        payload = json.dumps({"action": action, **kwargs}).encode()
        req = urllib.request.Request(
            self._daemon_url,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=300) as resp:
                return json.loads(resp.read())
        except urllib.error.HTTPError as e:
            body = e.read().decode()
            raise RuntimeError(f"Daemon returned {e.code}: {body}") from e
        except urllib.error.URLError as e:
            raise RuntimeError(f"Cannot reach daemon at {self._daemon_url}: {e}") from e

    def setup(self) -> None:
        result = self._daemon_call("check_device")
        if not result.get("connected"):
            raise RuntimeError("Daemon reports no device connected")

    def evaluate(
        self,
        pipeline_dir: Path,
        data_dir: Path,
        sample_ids: list[str] | None = None,
    ) -> EvalResult:
        # Build the test app
        result = self._daemon_call(self.build_action)
        if not result.get("ok"):
            raise RuntimeError(f"Build failed: {result.get('error', 'unknown')}")

        # Load ground truth
        gt_path = data_dir / "ground_truth.json"
        with open(gt_path) as f:
            data = json.load(f)

        all_images = data["images"]
        if sample_ids is not None:
            sample_set = set(sample_ids)
            all_images = [img for img in all_images if img["id"] in sample_set]

        gt_by_id = {img["id"]: img for img in all_images}

        manifest = {
            "images": [{"id": img["id"], "path": img["path"]} for img in all_images]
        }

        # Push files
        result = self._daemon_call("push_files", manifest=manifest)
        if not result.get("ok"):
            raise RuntimeError(
                f"Push failed: {result.get('errors', result.get('error'))}"
            )

        # Run tests
        result = self._daemon_call("run_tests")
        if not result.get("ok"):
            print(
                f"  Test run had issues: {result.get('stdout', '')}",
                file=sys.stderr,
            )

        # Pull results
        result = self._daemon_call("pull_results")
        if not result.get("ok"):
            raise RuntimeError(f"Pull failed: {result.get('error', 'unknown')}")

        # Parse results and compute metrics
        return self._compute_eval_result(result, gt_by_id)

    def _compute_eval_result(self, result: dict, gt_by_id: dict) -> EvalResult:
        sample_results = []
        references = []
        hypotheses = []
        latencies = []
        page_correct = 0
        page_total = 0

        for r in result["data"]["results"]:
            image_id = r["image_id"]
            gt = gt_by_id.get(image_id, {})
            extracted = r.get("extracted_text", "")
            reference = gt.get("text", "")

            cer = compute_cer(reference, extracted) if reference else 0.0

            extra: dict = {
                "expected": reference,
                "got": extracted,
                "cer": cer,
                "latency_ms": float(r.get("latency_ms", 0)),
            }

            if r.get("text_bounds"):
                extra["text_bounds"] = r["text_bounds"]
            if r.get("page_number_bounds"):
                extra["page_number_bounds"] = r["page_number_bounds"]

            if gt.get("page_number") is not None:
                page_total += 1
                got_page = r.get("page_number")
                if got_page == gt["page_number"]:
                    page_correct += 1
                extra["page_number"] = {
                    "expected": gt["page_number"],
                    "got": got_page,
                }

            sample_results.append(
                SampleResult(
                    sample_id=image_id,
                    score=cer,
                    error=r.get("error"),
                    extra=extra,
                )
            )

            if reference:
                references.append(reference)
                hypotheses.append(extracted)
                latencies.append(float(r.get("latency_ms", 0)))

        # Aggregate metrics
        agg_cer = jiwer.cer(references, hypotheses) if references else 1.0
        agg_wer = jiwer.wer(references, hypotheses) if references else 1.0
        mean_latency = sum(latencies) / len(latencies) if latencies else 0.0
        page_accuracy = page_correct / page_total if page_total > 0 else None

        if page_accuracy is not None:
            composite = _WEIGHT_BODY_CER * agg_cer + _WEIGHT_PAGE_NUMBER * (
                1.0 - page_accuracy
            )
        else:
            composite = agg_cer

        metrics: dict[str, float] = {
            "cer": agg_cer,
            "wer": agg_wer,
            "mean_latency_ms": mean_latency,
        }
        if page_accuracy is not None:
            metrics["page_number_accuracy"] = page_accuracy

        return EvalResult(
            score=composite,
            metrics=metrics,
            sample_results=sample_results,
        )
