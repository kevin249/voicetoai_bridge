# Third-party components

Build-time binary downloads are not committed to Git.

| Component | Source / version | License |
|---|---|---|
| sherpa-onnx Android AAR | [k2-fsa/sherpa-onnx v1.12.14](https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.14) | Apache-2.0 |
| Chinese KWS model | sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01 | Archive README declares Apache License 2.0 |
| ONNX Runtime bundled in AAR | [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) | MIT; upstream third-party notices also apply |
| Kotlin standard library | 2.1.20 | Apache-2.0 |
| Gradle wrapper | 8.13 | Apache-2.0 |
| JUnit (test only) | 4.13.2 | EPL-1.0 |

Model: encoder INT8, decoder FP32, joiner INT8 (epoch 12 / avg 2 / chunk 16 / left 64). URLs and SHA-256 hashes are pinned in `tools/prepare_deps.py`.

ChatGPT and Gemini are independent applications owned by their providers. They are not bundled, modified, or represented as sponsors of this project.
