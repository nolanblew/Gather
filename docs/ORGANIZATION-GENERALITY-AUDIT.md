# Gather organization: generality audit

Status: Design audit completed; PRD and TDR revised, application unchanged. Subsequent [quiet UX refinement](ORGANIZATION-UX.md) removes feature-enable switches and starter onboarding from the default flow; its interaction decisions take precedence.
Scope: Broader product usefulness, semantic clarity, implementation scope and bounded scale. This is a scenario review, not user research or measured load testing.

- [Revised PRD](TRIP-ORGANIZATION-PRD.md)
- [Revised technical design](TRIP-ORGANIZATION-TDR.md)

## Verdict

The original design had a sound foundation—optional fields, one canonical item, and independent numbering—but its city-only vocabulary and Japan-first location catalog were too narrow. Broaden the location concept to Places and keep the Japan trip as one worked example. Keep Recipients precise and optional instead of pretending it also provides task assignment. This supports more uses without turning Gather into a configurable database or project-management suite.

The document filenames remain stable to preserve existing links; their titles and proposed schema now describe general organization. These are pre-implementation design changes, so no city-to-place production migration is needed. Workspace v1/v2-to-v3 migration remains required when implementation begins.

## Findings and dispositions

| Finding | Impact | Revision |
| --- | --- | --- |
| City-only field | Local errands and Home/Work contexts fit poorly | Places accepts arbitrary city, store, home, work, online or other labels without geographic setup |
| Japan-only location rollout | Regional hardcoding and misleading general availability | Country-neutral catalog contract, disclosed coverage, tests in multiple countries; geography stays optional |
| Recipient can be mistaken for assignee | Creates false expectations about responsibility and access | Define it as For whom; no user-account link, notification, or task ownership |
| Multiple locations can mean alternatives or obligations | Checking once could falsely complete work needed at every site | Explicit OR semantics; independent obligations use independent rows |
| Gift/trip setup dominates onboarding | Ordinary users pay complexity cost | Optional settings and lightweight starters; blank capture must gain no required fields or taps |
| Geographic parent-child assumptions | A store assigned alone might unexpectedly vanish from a city view | Flat explicit memberships documented; no inferred containment |
| Numbering looks like a workflow system | Users could expect dependencies or multiple ordered subsequences | Saved sequence/rank only; disclose section-wide limitations |
| Location counts assume shopping checklists | Notes could be labeled incomplete incorrectly | Notes use all matching ideas; no completion filter or “remaining” label |
| Validation favors one traveler | A design can pass the original scenario and still fail other users | Added errands, household, project, recipe, wishlist, plain-list, multilingual and non-Japan cases |
| Unlimited-scale implications | Existing bounded JSON model is not an enterprise platform | Retain explicit limits, linear view derivation, bounded catalog delivery and measured client response limits |

## Scenario coverage

| Scenario | Configuration | Supported outcome | Deliberate boundary |
| --- | --- | --- | --- |
| Japan gift shopping | Places + Recipients; optional numbering | Tokyo OR Kyoto, several gifts/person, self, single completion | No stock verification or itinerary routing |
| Weekly errands | Places only | Find work doable at either store or location | No inferred chain/branch membership |
| Household chores | Optional Places + numbered checklist | Home/Office context, reusable template, explicit order | Work required at both sites uses two rows |
| Birthday wishlist | Recipients and/or numbered notes | Ranked ideas with no checkboxes or location setup | No per-person private rows inside shared lists |
| Project preparation | Sections + numbering | Ordered checklist and existing sharing | No task assignees, dependencies or deadlines added |
| Recipe steps | Numbered checklist | Stable step numbers under checks and filtering | Ingredient-only sections cannot be excluded from numbering; use description or separate list |
| Plain groceries | No new fields | Same quick capture and completion as today | Rich metadata is optional |
| Reading/idea list | Notes; optional numbering | Ordered ideas without purchase assumptions | No arbitrary custom-field builder |
| Travel outside Japan | Places + optional mapped city areas | Same manual organization and supported city suggestions | Geographic coverage is disclosed and incomplete |

## What remains intentionally specific

Recipients should stay a clearly named specialist capability, just as numbering is optional. Replacing it with an ambiguous People field would make the design superficially more general while weakening meaning. If later research supports task ownership, design Assignee separately with account permissions and notification semantics.

Places are an explicit context dimension, not generic tags. Labels such as urgent, vegan or research topics are not locations and should not be stuffed into Places. Existing sections and notes cover basic organization; a future tags feature can be evaluated on its own merits. Neither the original request nor this audit justifies building an arbitrary field system now.

The release does not solve every recipe or project workflow. In particular, numbering only selected sections and offline travel access remain useful follow-ups. The documents now state these limits instead of presenting the release as a complete recipe manager, task manager, or travel planner.

## Acceptance changes

Phase 1 adds A15–A19 to the original gates. Phase 2 adds A20 for non-Japan coverage and notes-specific location behavior. Usability participants must span more than the original travel/gift scenario, and basic list capture must require no additional mandatory fields or taps. Proposed scale/performance targets remain unmeasured until implementation.

## Implementation consequence

Use list-scoped `places`, `placeIds`, `PlaceDefinition`, `PlaceAvailability`, and `place.*` commands from the revised TDR. Do not implement the superseded city-only names from the initial draft. Keep Recipients separate, preserve granular commands and shared completion, and retain the existing migration, conflict, backup and rollback safeguards. No new infrastructure or global people/place directory is warranted by this audit.
