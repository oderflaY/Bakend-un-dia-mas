#!/usr/bin/env python3
"""Genera los Localizable.strings de iOS a partir de los strings.xml compartidos.

    python3 scripts/generar-textos-ios.py

La app de Android y la de iOS muestran los mismos textos: se editan solo en
shared/src/commonMain/composeResources y este script los copia a iosApp.
Los plurales quedan como dos claves, "nombre#one" y "nombre#other".
"""
import pathlib
import re
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = {"es": "values", "en": "values-en"}
OUT = ROOT / "iosApp" / "iosApp"


def to_ios(text: str) -> str:
    # Android escapes -> plain text, then Android format specifiers -> Foundation ones.
    text = re.sub(r"\\(['\"@?])", r"\1", text).replace("\\n", "\n")
    text = re.sub(r"%(\d+)\$s", r"%\1$@", text)
    text = re.sub(r"%(\d+)\$d", r"%\1$ld", text)
    return text


def quote(text: str) -> str:
    return '"' + text.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def main() -> None:
    for lang, folder in SOURCES.items():
        tree = ET.parse(ROOT / "shared/src/commonMain/composeResources" / folder / "strings.xml")
        lines = ["/* Generado por scripts/generar-textos-ios.py; no editar a mano. */", ""]
        for node in tree.getroot():
            name = node.get("name")
            if node.tag == "string":
                lines.append(f"{quote(name)} = {quote(to_ios(node.text or ''))};")
            elif node.tag == "plurals":
                for item in node:
                    lines.append(f"{quote(name + '#' + item.get('quantity'))} = {quote(to_ios(item.text or ''))};")
        target = OUT / f"{lang}.lproj" / "Localizable.strings"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"{target.relative_to(ROOT)}: {len(lines) - 2} textos")


if __name__ == "__main__":
    main()
