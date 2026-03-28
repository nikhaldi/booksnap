import { requireNativeModule } from "expo-modules-core";

import type { ScanOptions, ScanResult } from "./types";

export type { BoundingBox, ScanOptions, ScanResult } from "./types";

const NativeModule = requireNativeModule("BookSnap");

/**
 * Scan a book page from a photo and extract text.
 *
 * Applies platform-optimized preprocessing (document detection, perspective
 * correction, deskewing), runs on-device OCR (Vision on iOS, ML Kit on
 * Android), and assembles text blocks into reading order with paragraph breaks.
 *
 * @param uri - File URI or path to the photo.
 * @param options - Optional scanning configuration.
 * @returns Extracted text with paragraph breaks.
 */
export async function scanPage(
  uri: string,
  options?: ScanOptions,
): Promise<ScanResult> {
  const opts: Record<string, unknown> = {};
  if (options?.spellCheck !== undefined) {
    opts.spellCheck = options.spellCheck;
  }
  const raw = await NativeModule.scanPage(uri, opts);
  return {
    text: raw.text,
    textBounds: raw.textBounds,
    pageNumber: raw.pageNumber ?? undefined,
    pageNumberBounds: raw.pageNumberBounds ?? undefined,
  };
}
