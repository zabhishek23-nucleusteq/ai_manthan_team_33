# Design tokens - ExpenseFlow

Single source: `frontend/src/design/theme.ts` (Ant Design `ConfigProvider theme={{ token, components }}`), light + dark via `theme.darkAlgorithm`. No hard-coded colours in components: use Ant tokens (`theme.useToken()`) or the maps below exported from `theme.ts` (`statusTag`, `flagTag`, `chartPalette`).

## 1. Seed tokens (light)
| Token | Value | Use |
|---|---|---|
| `colorPrimary` | `#1f5fbf` | primary buttons, links, active menu (contrast 6.2:1 on white) |
| `colorSuccess` | `#2e7d32` | approved, paid |
| `colorWarning` | `#b26a00` | pending, warnings (text contrast >= 4.5:1) |
| `colorError` | `#c62828` | rejected, violations, destructive |
| `colorInfo` | `#1f5fbf` | info alerts |
| `colorTextBase` | `#1a1f2b` | body text |
| `colorBgLayout` | `#f5f7fa` | page background |
| `borderRadius` | `6` | all controls and cards |
| `fontFamily` | `system-ui, -apple-system, "Segoe UI", Roboto, "Noto Sans", sans-serif` | no web fonts (offline-safe) |
| `fontSize` | `14` | base; `fontSizeLG` 16 for mobile inputs (prevents iOS zoom) |
| `controlHeight` | `36` | desktop; `controlHeightLG` 44 on mobile (touch target >= 44 px) |
| `lineHeight` | `1.5715` | Ant default |

Dark mode: same seeds + `theme.darkAlgorithm`; toggle in the header, default follows `prefers-color-scheme`, choice kept in `localStorage` (wrapped in try/catch).

## 2. Spacing and layout
| Token | px | Use |
|---|---|---|
| `marginXXS` | 4 | icon to text |
| `marginXS` | 8 | inside compact rows, tag gaps |
| `marginSM` | 12 | form item gap on mobile |
| `margin` | 16 | page gutter at 360 px, card padding mobile |
| `marginLG` | 24 | page gutter desktop, card padding desktop, section gap |
| `marginXL` | 32 | between page sections |

Breakpoints (Ant `Grid`): `xs < 576` (phone, 360 px target), `sm 576`, `md 768`, `lg 992` (sider menu shows, split views), `xl 1200`.
Content max width 1280 px. Tables: `size="small"` on staff screens (dense), `size="middle"` elsewhere.

## 3. Typography
| Role | Ant | Size / weight |
|---|---|---|
| Page title | `Typography.Title level={3}` | 24 / 600 |
| Section title | `Title level={5}` | 16 / 600 |
| Body | `Text` | 14 / 400 |
| Secondary / help | `Text type="secondary"` | 14 (12 for captions) |
| KPI value | `Statistic` | 24-30 / 600, tabular numbers (`font-variant-numeric: tabular-nums`) |
| Money in tables | right-aligned, tabular numbers, `en-IN` grouping: `1,00,000 INR` |

Dates: `DD MMM YYYY` (e.g. `27 Nov 2026`), time `HH:mm` IST; relative age ("3d 4h") with exact time in a tooltip.

## 4. Claim status tags (`statusTag(status)`)
Colour is never the only signal: each tag has text and an icon.

| ClaimStatus | Label | Ant `Tag` color | Icon |
|---|---|---|---|
| `DRAFT` | Draft | `default` | `EditOutlined` |
| `SUBMITTED` | Submitted | `blue` | `SendOutlined` |
| `PENDING_MANAGER` | Pending manager | `gold` | `ClockCircleOutlined` |
| `PENDING_AUDITOR` | Pending finance auditor | `gold` | `ClockCircleOutlined` |
| `PENDING_HEAD` | Pending finance head | `gold` | `ClockCircleOutlined` |
| `CLARIFICATION_REQUESTED` | Needs your reply | `orange` | `QuestionCircleOutlined` |
| `AUTO_APPROVED` | Auto-approved | `green` | `ThunderboltOutlined` |
| `APPROVED` | Approved | `green` | `CheckCircleOutlined` |
| `AUTO_REJECTED` | Auto-rejected | `red` | `StopOutlined` |
| `REJECTED` | Rejected | `red` | `CloseCircleOutlined` |
| `IN_PAYOUT` | In payout | `purple` | `BankOutlined` |
| `PAID` | Paid | `cyan` | `DollarOutlined` |

Status names are provisional until the claim state machine ADR; only this table and `statusTag()` change on a rename.

## 5. Policy violation / flag tags (`flagTag(code)`)
Colour-blind safe: distinct icon + text per flag; hues chosen from the Okabe-Ito set so they stay distinguishable under protanopia/deuteranopia.

| Flag | Label | Colour (hex, Okabe-Ito) | Icon | Meaning |
|---|---|---|---|---|
| `OVER_LIMIT` | Over limit | `#D55E00` vermillion | `RiseOutlined` | amount above category limit (meals/day, hotel/night, cab/trip) |
| `NO_RECEIPT` | Receipt missing | `#CC79A7` reddish purple | `FileExclamationOutlined` | > 500 INR without receipt |
| `DUPLICATE` | Possible duplicate | `#0072B2` blue | `CopyOutlined` | same vendor+amount+date or same receipt hash |
| `WEEKEND` | Weekend spend | `#E69F00` orange | `CalendarOutlined` | spend date Sat/Sun, manager attention |
| `SAMPLE_AUDIT` | Sample audit | `#009E73` bluish green | `AuditOutlined` | random 10% of auto-approved |

Tags use the colour as border + icon colour and dark text on a light tint (`<hex>1A` background) so label text keeps >= 4.5:1 contrast.

## 6. Policy outcome (submit result)
| Outcome | Ant | Colour |
|---|---|---|
| Auto-approve | `Result status="success"` / `Alert type="success"` | `colorSuccess` |
| Route for approval | `Result status="info"` + `Steps` of the chain | `colorPrimary` |
| Auto-reject | `Result status="error"` / `Alert type="error"` with reason list | `colorError` |

## 7. Chart palette (`chartPalette`)
Okabe-Ito order: `#0072B2`, `#E69F00`, `#009E73`, `#D55E00`, `#CC79A7`, `#56B4E9`, `#F0E442`, `#000000` (dark mode: last one `#FFFFFF`). Max 8 series; more -> "Other". Bars labelled with values; legends always shown; "Show as table" toggle on every chart.

## 8. Focus and motion
- Focus ring: Ant `controlOutline` (2 px, primary at 20 % alpha) + `outline: 2px solid colorPrimary` on custom focusable rows.
- Respect `prefers-reduced-motion`: `ConfigProvider theme={{ token: { motion: false } }}` when set.
