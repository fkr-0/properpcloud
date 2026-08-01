#!/usr/bin/env python3
"""Validate properpcloud's normative YAML specification set."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import yaml


class UniqueKeyLoader(yaml.SafeLoader):
    """Safe YAML loader that rejects duplicate mapping keys."""


def construct_mapping(
    loader: UniqueKeyLoader,
    node: yaml.MappingNode,
    deep: bool = False,
) -> dict[Any, Any]:
    mapping: dict[Any, Any] = {}
    for key_node, value_node in node.value:
        key = loader.construct_object(key_node, deep=deep)
        if key in mapping:
            raise yaml.constructor.ConstructorError(
                "while constructing a mapping",
                node.start_mark,
                f"duplicate key: {key!r}",
                key_node.start_mark,
            )
        mapping[key] = loader.construct_object(value_node, deep=deep)
    return mapping


UniqueKeyLoader.add_constructor(
    yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG,
    construct_mapping,
)


def load(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as stream:
        value = yaml.load(stream, Loader=UniqueKeyLoader)
    if not isinstance(value, dict) or not value:
        raise ValueError(f"{path}: expected a non-empty top-level mapping")
    return value


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def main() -> int:
    root = Path(__file__).resolve().parents[1]
    spec_dir = root / "spec"
    errors: list[str] = []
    documents: dict[str, dict[str, Any]] = {}

    for path in sorted(spec_dir.glob("*.yml")):
        try:
            documents[path.name] = load(path)
            print(f"spec: parsed {path.relative_to(root)}")
        except (OSError, ValueError, yaml.YAMLError) as error:
            errors.append(str(error))

    manifest = documents.get("manifest.yml", {}).get("specification", {})
    for entry in manifest.get("source_of_truth", []):
        filename = entry.get("file")
        require(
            isinstance(filename, str) and filename in documents,
            f"manifest references missing specification file: {filename!r}",
            errors,
        )

    product = documents.get("product.yml", {}).get("product", {})
    requirements = product.get("requirements", {})
    require(isinstance(requirements, dict), "product requirements must be a mapping", errors)
    requirement_ids = set(requirements) if isinstance(requirements, dict) else set()

    for release_id, release in product.get("release_slices", {}).items():
        for requirement_id in release.get("requirements", []):
            require(
                requirement_id in requirement_ids,
                f"release {release_id} references unknown requirement {requirement_id}",
                errors,
            )

    cases = documents.get("use-cases.yml", {}).get("use_cases", {}).get("cases", {})
    traced_requirements: set[str] = set()
    for case_id, case in cases.items():
        traces = case.get("traces", [])
        require(bool(traces), f"use case {case_id} has no requirement trace", errors)
        for requirement_id in traces:
            traced_requirements.add(requirement_id)
            require(
                requirement_id in requirement_ids,
                f"use case {case_id} traces unknown requirement {requirement_id}",
                errors,
            )

    must_requirements = {
        requirement_id
        for requirement_id, requirement in requirements.items()
        if requirement.get("priority") == "must"
    }
    for requirement_id in sorted(must_requirements - traced_requirements):
        errors.append(f"must requirement lacks a use case: {requirement_id}")

    if errors:
        for error in errors:
            print(f"spec error: {error}", file=sys.stderr)
        return 1

    print(
        "spec: validated "
        f"{len(documents)} documents, {len(requirement_ids)} requirements, "
        f"and {len(cases)} use cases"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
