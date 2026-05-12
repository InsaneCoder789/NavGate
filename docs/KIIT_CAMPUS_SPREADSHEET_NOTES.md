# KIIT Campus Spreadsheet Notes

Source file:
`/Users/rohanc/Downloads/KIIT_Complete_Campus_Wise_Locations.xlsx`

## Workbook overview

This workbook is a campus location dataset for KIIT, apparently intended to organize campus places by:
- campus number or `Unassigned`
- category
- human-readable location name
- Google Maps link when available

It is already split into summary sheets and per-campus sheets.

## Sheet structure

Workbook sheets:
- `All_Locations`
- `Campus_Summary`
- `Category_Summary`
- `Unassigned_Locations`
- `Campus_1` through `Campus_23`
- `Campus_25`

Notably missing:
- `Campus_24`

### Primary master sheet

`All_Locations`
- 326 data rows
- 4 columns
- Headers:
  - `Campus`
  - `Category`
  - `Location Name`
  - `Google Maps URL`

This is the main sheet that looks most useful as the source of truth.

### Summary sheets

`Campus_Summary`
- Counts per campus by category
- 24 data rows plus header
- Categories are split into separate columns
- Category naming is inconsistent because some categories appear twice in different cases, for example:
  - `Boys Hostel` and `Boys Hostel` variant in uppercase form
  - `Cafeteria` and `CAFETERIA`
  - `Sports` and `SPORTS`
  - `Guest House` and `GUEST HOUSE`

`Category_Summary`
- Counts total locations by category
- 15 distinct raw category labels
- Also reveals normalization issues in category naming

### Unassigned sheet

`Unassigned_Locations`
- 151 data rows
- 3 columns:
  - `Category`
  - `Location Name`
  - `Google Maps URL`

This means almost half the dataset has not yet been mapped to a campus.

### Campus sheets

Each `Campus_X` sheet contains:
- `Category`
- `Location Name`
- `Google Maps URL`

These appear to be filtered views of the master sheet rather than independent datasets.

## Key totals

From `All_Locations`:
- Total locations: 326
- Assigned to a campus: 175
- Unassigned: 151
- Rows with Google Maps URL present: 242
- Rows missing Google Maps URL: 84

## Campus distribution

Largest assigned campuses by number of entries:
- `Campus 3`: 22
- `Campus 1`: 18
- `Campus 13`: 13
- `Campus 15`: 12
- `Campus 25`: 11
- `Campus 12`: 11
- `Campus 10`: 11
- `Campus 9`: 10
- `Campus 11`: 8
- `Campus 6`: 7

Smallest assigned campuses:
- `Campus 19`: 1
- `Campus 21`: 1
- `Campus 22`: 2
- `Campus 20`: 3
- `Campus 23`: 3
- `Campus 4`: 3

## Raw categories found

Distinct raw category labels:
- `BOYS HOSTEL`
- `Boys Hostel`
- `CAFETERIA`
- `Cafeteria`
- `Commercial`
- `GIRLS HOSTELS`
- `GUEST HOUSE`
- `Guest House`
- `KAFE`
- `KIIT`
- `KIMS`
- `KISS`
- `Other`
- `SPORTS`
- `Sports`

Category counts from the raw sheet:
- `KIIT`: 88
- `SPORTS`: 80
- `BOYS HOSTEL`: 33
- `Other`: 22
- `GIRLS HOSTELS`: 21
- `CAFETERIA`: 17
- `Boys Hostel`: 12
- `Sports`: 12
- `Cafeteria`: 9
- `KAFE`: 7
- `GUEST HOUSE`: 7
- `KIMS`: 6
- `KISS`: 6
- `Commercial`: 3
- `Guest House`: 3

## Data quality observations

### 1. Category normalization is needed

Several categories are duplicates with different capitalization:
- `BOYS HOSTEL` vs `Boys Hostel`
- `CAFETERIA` vs `Cafeteria`
- `GUEST HOUSE` vs `Guest House`
- `SPORTS` vs `Sports`

These should be normalized before using the data in the app or backend.

### 2. `KAFE` may need semantic review

`KAFE` looks like a special cafeteria-related category or a naming variant. It should be clarified whether it should remain separate or be merged into `Cafeteria`.

### 3. Campus assignment is incomplete

151 of 326 rows are marked `Unassigned`, which is a major gap if the goal is campus-wise routing, browsing, or filtering.

### 4. Google Maps coverage is partial

84 rows do not have a map URL.
This matters if we want to derive coordinates reliably.

### 5. Some rows contain formatting noise

A few values include:
- leading spaces
- trailing spaces
- inconsistent punctuation
- mixed casing in names

Examples seen:
- `Chintan Building (Administrative Block)  `
- ` https://maps.google.com/?q=...`
- `KIIT HR Department               `

This should be trimmed during import.

### 6. Some semantic labels may be inaccurate

Example seen:
- `Campus 9 girls hostel` is currently under `Boys Hostel`

This suggests a manual review pass is still needed.

## What this spreadsheet is good for

This workbook is already useful as a first-pass POI dataset for:
- campus-wise place browsing
- category filters
- initial backend seeding
- route destination lookup
- search suggestions
- data cleanup workflows

## What it is not yet ready for

Without cleanup, it is not fully ready as a production POI source for:
- strict campus filtering
- reliable category analytics
- precise routing graph generation
- automatic map placement for every POI
- polished user-facing search and labeling

## Recommended data model from this sheet

A clean import target could look like:
- `id`
- `name`
- `normalized_name`
- `campus_id`
- `campus_label`
- `category_raw`
- `category_normalized`
- `google_maps_url`
- `latitude`
- `longitude`
- `is_unassigned`
- `source_sheet`
- `notes`

## Suggested next cleanup steps

1. Normalize categories into one canonical set.
2. Parse all Google Maps links into latitude/longitude fields.
3. Trim whitespace and standardize capitalization in names.
4. Review `Unassigned` rows and map as many as possible to a campus.
5. Flag ambiguous or suspicious entries for manual review.
6. Export the cleaned result into a backend-ready CSV or JSON seed file.
