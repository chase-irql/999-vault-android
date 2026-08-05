import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEFAULT_FILE = path.resolve(HERE, "..", "docs", "platform-parity.yaml");
const target = path.resolve(process.argv[2] ?? DEFAULT_FILE);

const DOMAINS = new Set([
    "navigation",
    "playback",
    "downloads",
    "storage",
    "account",
    "library",
    "catalog",
    "radio",
    "wrapped",
    "presentation",
    "lifecycle",
    "security",
]);
const PARITY = new Set(["exact", "adapted", "android_only", "desktop_only", "deferred", "not_applicable"]);
const STATUS = new Set(["planned", "implemented", "verified", "blocked"]);
const REQUIRED = [
    "id",
    "domain",
    "desktop_sources",
    "desktop_behavior",
    "android_behavior",
    "parity",
    "rationale",
    "android_sources",
    "tests",
    "status",
    "evidence",
];
const ARRAY_FIELDS = ["desktop_sources", "android_sources", "tests", "evidence"];
const TEXT_FIELDS = ["id", "domain", "desktop_behavior", "android_behavior", "parity", "rationale", "status"];

const failures = [];

function fail(location, message) {
    failures.push(`${location}: ${message}`);
}

function nonEmptyStrings(value) {
    return Array.isArray(value) && value.length > 0 && value.every((entry) => typeof entry === "string" && entry.trim().length > 0);
}

let document;
try {
    document = JSON.parse(fs.readFileSync(target, "utf8"));
} catch (error) {
    console.error(`Parity validation failed: ${target} is not valid JSON/YAML 1.2 JSON syntax.`);
    console.error(error instanceof Error ? error.message : String(error));
    process.exit(1);
}

if (!Array.isArray(document)) {
    fail("root", "must be an array");
} else if (document.length === 0) {
    fail("root", "must contain at least one feature");
}

const ids = new Set();
for (const [index, feature] of (Array.isArray(document) ? document : []).entries()) {
    const location = `feature[${index}]`;
    if (!feature || typeof feature !== "object" || Array.isArray(feature)) {
        fail(location, "must be an object");
        continue;
    }

    for (const field of REQUIRED) {
        if (!Object.hasOwn(feature, field)) fail(location, `is missing required field ${field}`);
    }
    for (const field of TEXT_FIELDS) {
        if (Object.hasOwn(feature, field) && typeof feature[field] !== "string") {
            fail(`${location}.${field}`, "must be a string");
        }
    }
    for (const field of ARRAY_FIELDS) {
        if (!Object.hasOwn(feature, field)) continue;
        if (!Array.isArray(feature[field])) {
            fail(`${location}.${field}`, "must be an array");
        } else if (!feature[field].every((entry) => typeof entry === "string" && entry.trim().length > 0)) {
            fail(`${location}.${field}`, "must contain only non-empty strings");
        }
    }
    if (Object.hasOwn(feature, "notes") && typeof feature.notes !== "string") {
        fail(`${location}.notes`, "must be a string when present");
    }

    if (typeof feature.id === "string") {
        if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(feature.id)) {
            fail(`${location}.id`, "must be stable kebab-case");
        }
        if (ids.has(feature.id)) fail(`${location}.id`, `duplicates ${feature.id}`);
        ids.add(feature.id);
    }
    if (typeof feature.domain === "string" && !DOMAINS.has(feature.domain)) {
        fail(`${location}.domain`, `has invalid value ${JSON.stringify(feature.domain)}`);
    }
    if (typeof feature.parity === "string" && !PARITY.has(feature.parity)) {
        fail(`${location}.parity`, `has invalid value ${JSON.stringify(feature.parity)}`);
    }
    if (typeof feature.status === "string" && !STATUS.has(feature.status)) {
        fail(`${location}.status`, `has invalid value ${JSON.stringify(feature.status)}`);
    }
    if (!nonEmptyStrings(feature.desktop_sources)) {
        fail(`${location}.desktop_sources`, "must contain at least one exact desktop source reference");
    }
    if (typeof feature.desktop_behavior === "string" && feature.desktop_behavior.trim().length === 0) {
        fail(`${location}.desktop_behavior`, "must not be empty");
    }
    if (typeof feature.android_behavior === "string" && feature.android_behavior.trim().length === 0) {
        fail(`${location}.android_behavior`, "must not be empty");
    }
    if (feature.parity !== "exact" && (typeof feature.rationale !== "string" || feature.rationale.trim().length === 0)) {
        fail(`${location}.rationale`, `is required for ${feature.parity ?? "non-exact"} parity`);
    }
    if (["implemented", "verified"].includes(feature.status)) {
        if (!nonEmptyStrings(feature.android_sources)) {
            fail(`${location}.android_sources`, `must contain implementation references when status is ${feature.status}`);
        }
        if (!nonEmptyStrings(feature.tests)) {
            fail(`${location}.tests`, `must contain test references when status is ${feature.status}`);
        }
    }
    if (feature.status === "verified" && !nonEmptyStrings(feature.evidence)) {
        fail(`${location}.evidence`, "must contain screenshot, log, or report references when status is verified");
    }
}

if (failures.length) {
    console.error(`Parity validation failed with ${failures.length} error${failures.length === 1 ? "" : "s"}:`);
    for (const failure of failures) console.error(`- ${failure}`);
    process.exit(1);
}

console.log(`Parity validation passed: ${document.length} unique features in ${path.relative(process.cwd(), target) || target}.`);
