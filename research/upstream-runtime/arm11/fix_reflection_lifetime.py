"""Keep TeaVM 0.16.0 C reflection descriptors alive after class initialization."""

import argparse
import re
from pathlib import Path


CLASS_INITIALIZER = re.compile(
    r"^void (?P<name>sysinitclass_\w+)\(\) \{\n(?P<body>.*?)^\}$",
    re.MULTILINE | re.DOTALL,
)
REFLECTION_ASSIGNMENT = re.compile(
    r"^(?P<target>(?P<indent>[ \t]*)vt_\d+->reflection) = &\(TeaVM_ClassReflection\) "
    r"(?P<descriptor>\{\n.*?^(?P=indent)\});$",
    re.MULTILINE | re.DOTALL,
)
DESCRIPTOR_MARKER = b"->reflection = &(TeaVM_ClassReflection)"


def rewrite_class_initializer(match):
    body = match["body"]
    assignment = REFLECTION_ASSIGNMENT.search(body)
    if assignment is None:
        return match[0]

    # A block-scope compound literal and its nested class lists expire when
    # sysinitclass returns. At file scope, both have static storage duration.
    name = match["name"] + "_reflection"
    declaration = f"static TeaVM_ClassReflection {name} = {assignment['descriptor']};\n"
    body = body[:assignment.start()] + f"{assignment['target']} = &{name};" + body[assignment.end():]
    return declaration + f"void {match['name']}() {{\n{body}}}"


def fix_reflection_lifetime(generated):
    if not (generated / "all.c").is_file():
        raise ValueError(f"No generated TeaVM C program in {generated}")
    count = 0
    for path in (generated / "c").rglob("*.c"):
        raw = path.read_bytes()
        if DESCRIPTOR_MARKER not in raw:
            continue
        source = raw.decode("utf-8").replace("\r\n", "\n")
        updated = CLASS_INITIALIZER.sub(rewrite_class_initializer, source)
        if DESCRIPTOR_MARKER.decode() in updated:
            raise ValueError(f"Unrecognized TeaVM reflection initializer in {path}")
        count += raw.count(DESCRIPTOR_MARKER)
        path.write_text(updated, encoding="utf-8", newline="\n")
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("generated", type=Path)
    args = parser.parse_args()
    print(f"Updated {fix_reflection_lifetime(args.generated)} reflection initializers")
