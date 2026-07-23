//
//  GreenScreenVideoComposition.swift
//  ReactNativeVideo
//
//  Core Image chroma-style key aligned loosely with the Android GL shader (green-dominant pixels → transparent).
//

import AVFoundation
import CoreImage
import Foundation

enum GreenScreenVideoComposition {
  private static let kernelSource = """
  kernel vec4 greenScreen(__sample s) {
    vec3 c = s.rgb;
    float maxrb = max(c.r, c.b);
    float greenBias = c.g - maxrb;
    float a = clamp(1.0 - smoothstep(0.05, 0.5, greenBias), 0.0, 1.0) * s.a;
    return vec4(c.rgb, a);
  }
  """

  private static let colorKernel: CIColorKernel? = CIColorKernel(source: kernelSource)

  static func make(for asset: AVAsset) -> AVVideoComposition? {
    guard let kernel = colorKernel else {
      return nil
    }

    return AVVideoComposition(asset: asset, applyingCIFiltersWithHandler: { request in
      let source = request.sourceImage.clampedToExtent()
      guard let output = kernel.apply(extent: source.extent, arguments: [source]) else {
        request.finish(with: source.cropped(to: request.sourceImage.extent), context: nil)
        return
      }
      request.finish(with: output.cropped(to: request.sourceImage.extent), context: nil)
    })
  }
}
