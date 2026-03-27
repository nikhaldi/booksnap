/** Axis-aligned bounding box in pixel coordinates of the original image. */
export interface BoundingBox {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Result of scanning a single page. */
export interface ScanResult {
  /** Clean extracted text with paragraph breaks. */
  text: string;
  /** Bounding box of the body text region in the input image (pixels). */
  textBounds: BoundingBox;
  /** Page number if detected (e.g. from top/bottom margin). */
  pageNumber?: number;
  /** Bounding box of the page number in the original image (pixels). */
  pageNumberBounds?: BoundingBox;
}

/** Options for page scanning. */
export interface ScanOptions {
  /** Enable/disable spell correction (default: true). */
  spellCheck?: boolean;
}
