# Third-party notices for the native Rime engine shipped in this APK

This APK bundles a real librime engine plus its native dependency closure under
`lib/<abi>/`, and Rime schema/dictionary data under `assets/rime/`.
The files below are the license texts of everything redistributed.

## Native libraries (`lib/arm64-v8a/`, `lib/x86_64/`)

Prebuilt **bionic** (Android/Termux) packages were obtained from the Termux
package mirror `https://mirrors.cqupt.edu.cn/termux/apt/termux-main/`, unpacked
and re-packaged verbatim; they were not rebuilt for this project.

| file | upstream | version | license | SPDX |
| --- | --- | --- | --- | --- |
| `librime.so` | rime/librime | 1.17.0-1 | BSD-3-Clause | `librime-BSD-3-Clause.txt` |
| `libglog.so` | google/glog | 0.7.1-2 | BSD-3-Clause | `glog-BSD-3-Clause.txt` |
| `libgflags.so`, `libgflags_nothreads.so` | gflags/gflags | 2.3.1 | BSD-3-Clause | `gflags-BSD-3-Clause.txt` |
| `libyaml-cpp.so` | jbeder/yaml-cpp | 0.9.0 | MIT | `yaml-cpp-MIT.txt` |
| `libleveldb.so` | google/leveldb | 1.23-4 | BSD-3-Clause | `leveldb-BSD-3-Clause.txt` |
| `libsnappy.so` | google/snappy | 1.3.1 | BSD-3-Clause | `snappy-BSD-3-Clause.txt` |
| `libmarisa.so` | s-yata/marisa-trie | 0.3.1-1 | BSD-2-Clause / LGPL-2.1 | `marisa-trie-BSD-2-Clause-or-LGPL-2.1.txt` |
| `libopencc.so` | BYVoid/OpenCC | 1.4.2 | Apache-2.0 | `opencc-Apache-2.0.txt` |
| `libc++_shared.so` | llvm/llvm-project (libc++) | NDK r27 / Termux `libc++_30` | Apache-2.0 WITH LLVM-exception | `libcxx-Apache-2.0-LLVM-exception.txt` |

Notes:

* librime statically contains Boost code; the Boost Software License 1.0 text is
  included as `boost-BSL-1.0.txt`.
* `libmarisa.so` upstream is dual-licensed BSD-2-Clause / LGPL-2.1; the shipped
  license text covers both options.
* The JNI shim `librime_jni.so` itself is original code of this project and is
  not a third-party component.

## Rime data (`assets/rime/`)

| file | upstream | license | SPDX |
| --- | --- | --- | --- |
| `luna_pinyin.dict.yaml` | rime/rime-luna-pinyin | LGPL-3.0 | `rime-luna-pinyin-LGPL-3.0.txt` |
| `luna_pinyin.schema.yaml` | derived from rime/rime-luna-pinyin (trimmed, see header) | LGPL-3.0 | `rime-luna-pinyin-LGPL-3.0.txt` |
| `default.yaml` | minimal config written for this project, based on rime/rime-prelude defaults | LGPL-3.0 | `rime-prelude-LGPL-3.0.txt` |
| `essay.txt` | rime/rime-essay (preset vocabulary / word frequency table) | LGPL-3.0 | `rime-essay-LGPL-3.0.txt` |

The LGPL-3.0 texts are shipped as-is; the schema/dictionary remain under the
same license as upstream. Source for every LGPL component is obtainable from
the upstream repositories named above.
