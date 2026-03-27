import ExpoModulesCore

/// Expo module wrapper — thin adapter around BookSnapPipeline.
///
/// Pure iOS consumers use BookSnapPipeline directly;
/// React Native consumers go through this module.
public class BookSnapModule: Module {

  private let pipeline = BookSnapPipeline()

  public func definition() -> ModuleDefinition {
    Name("BookSnap")

    AsyncFunction("scanPage") { (inputPath: String, options: [String: Any]) -> [String: Any] in
      let result = try await self.pipeline.processImage(from: inputPath, options: options)

      var map: [String: Any] = [
        "text": result.text,
        "textBounds": self.boundsToMap(result.textBounds),
      ]
      if let pageNumber = result.pageNumber {
        map["pageNumber"] = pageNumber
      }
      if let pageNumberBounds = result.pageNumberBounds {
        map["pageNumberBounds"] = self.boundsToMap(pageNumberBounds)
      }
      return map
    }
  }

  private func boundsToMap(_ b: BoundingBox) -> [String: Int] {
    ["x": b.x, "y": b.y, "width": b.width, "height": b.height]
  }
}
