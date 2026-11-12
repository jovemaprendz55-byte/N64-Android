#!/usr/bin/env python3
import pathlib
import struct
import sys
vertex_path, fragment_path, output_path = map(pathlib.Path, sys.argv[1:4])
def words(path):
    data = path.read_bytes()
    if len(data) % 4:
        raise SystemExit(f"Invalid SPIR-V byte count: {path}")
    return struct.unpack("<" + "I" * (len(data) // 4), data)
def array(name, values):
    rows = ["    " + ", ".join(f"0x{x:08x}u" for x in values[i:i+8]) for i in range(0, len(values), 8)]
    return f"static const uint32_t {name}[] = {{\n" + ",\n".join(rows) + "\n};\n"
content = "#pragma once\n#include <cstdint>\n\n"
content += array("triangle_vertex_spv", words(vertex_path)) + "\n"
content += array("triangle_fragment_spv", words(fragment_path))
output_path.parent.mkdir(parents=True, exist_ok=True)
output_path.write_text(content, encoding="utf-8")
