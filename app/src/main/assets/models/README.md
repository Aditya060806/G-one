# Language model

The model file is not in the repository: it is 1.1 GB. Fetch it with

```powershell
powershell -ExecutionPolicy Bypass -File tools\fetch_model.ps1
```

which downloads it to `app/src/main/assets/models/qwen.gguf` and checks it against the values below.
A different file renamed to `qwen.gguf` is not the same build.

| | |
|---|---|
| Model | Qwen2.5-1.5B-Instruct, GGUF, Q4_K_M |
| Source | `Qwen/Qwen2.5-1.5B-Instruct-GGUF` on Hugging Face |
| File | `qwen2.5-1.5b-instruct-q4_k_m.gguf` |
| Revision | `91cad51170dc346986eccefdc2dd33a9da36ead9` |
| Size | 1,117,320,736 bytes |
| SHA-256 | `6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e` |
| Licence | Apache-2.0 |

The file in the author's workspace was hashed on 2 October 2026 and matches this entry byte for byte.

The small sensor models the app also ships are in `app/src/main/assets/ml/`, are in the repository,
and are rebuilt by the scripts in `ml/`.
