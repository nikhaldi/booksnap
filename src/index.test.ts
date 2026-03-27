import { describe, it, expect, vi, beforeEach } from "vitest";

const mockScanPage = vi.fn();

vi.mock("expo-modules-core", () => ({
  requireNativeModule: () => ({ scanPage: mockScanPage }),
}));

// Import after mock setup — vi.mock is hoisted automatically
const { scanPage } = await import("./index");

describe("scanPage", () => {
  beforeEach(() => {
    mockScanPage.mockReset();
  });

  it("passes uri and empty options to native module", async () => {
    mockScanPage.mockResolvedValueOnce({
      text: "Hello",
      textBounds: { x: 0, y: 0, width: 100, height: 50 },
    });

    await scanPage("/path/to/image.jpg");

    expect(mockScanPage).toHaveBeenCalledWith("/path/to/image.jpg", {});
  });

  it("passes spellCheck option to native module", async () => {
    mockScanPage.mockResolvedValueOnce({
      text: "",
      textBounds: { x: 0, y: 0, width: 0, height: 0 },
    });

    await scanPage("/path.jpg", { spellCheck: false });

    expect(mockScanPage).toHaveBeenCalledWith("/path.jpg", { spellCheck: false });
  });

  it("maps native result to ScanResult", async () => {
    mockScanPage.mockResolvedValueOnce({
      text: "Page text",
      textBounds: { x: 10, y: 20, width: 300, height: 400 },
      pageNumber: 42,
      pageNumberBounds: { x: 100, y: 900, width: 30, height: 20 },
    });

    const result = await scanPage("/path.jpg");

    expect(result).toEqual({
      text: "Page text",
      textBounds: { x: 10, y: 20, width: 300, height: 400 },
      pageNumber: 42,
      pageNumberBounds: { x: 100, y: 900, width: 30, height: 20 },
    });
  });

  it("converts null optional fields to undefined", async () => {
    mockScanPage.mockResolvedValueOnce({
      text: "No page number",
      textBounds: { x: 0, y: 0, width: 100, height: 50 },
      pageNumber: null,
      pageNumberBounds: null,
    });

    const result = await scanPage("/path.jpg");

    expect(result.pageNumber).toBeUndefined();
    expect(result.pageNumberBounds).toBeUndefined();
  });
});
