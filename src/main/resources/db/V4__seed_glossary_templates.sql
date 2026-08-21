-- =============================================================================
-- Seed business glossary from global prompt rules
-- =============================================================================

INSERT INTO business_glossary (term, table_ref, column_ref, rule) VALUES
  ('revenue',
   'gmai.report_entitlements', 'ear',
   'Always use ear (Estimated Annual Revenue). True revenue is NOT in this dataset. Label output as "EAR", never "revenue".'),
  ('ear',
   'gmai.report_entitlements', 'ear',
   'Estimated Annual Revenue — default metric for all amount queries. Report in dollars/thousands/millions with commas; no cents unless asked.'),
  ('tcv',
   'gmai.report_entitlements', 'ear',
   'Total Contract Value = ear × (duration_days / 365). Compute this on the fly; do not look for a tcv column.'),
  ('rb',
   'gmai.report_entitlements', 'rb',
   'Renewal Baseline — use the rb column directly. Only show when user explicitly asks.'),
  ('active entitlements',
   'gmai.report_entitlements', 'is_current',
   'Always filter WHERE is_current = 1 to get active entitlements only.'),
  ('active',
   'gmai.report_entitlements', 'is_current',
   'Filter WHERE is_current = 1 to restrict to active records.'),
  ('new logo',
   'gmai.report_entitlements', NULL,
   'New logo = customer with NO entitlement prior to the stated date range. Exclude parent_name values that appear in entitlements before that range.'),
  ('new customer',
   'gmai.report_entitlements', NULL,
   'Same as new logo — exclude accounts that had any entitlement before the stated date range.'),
  ('customer',
   'gmai.report_entitlements', 'parent_name',
   'Default aggregation is at PARENT level using parent_name. Only drill to licensee_name when explicitly requested.'),
  ('account',
   'gmai.report_entitlements', 'parent_name',
   'Use parent_name for account-level queries. Group by parent_name unless individual licensees are requested.'),
  ('product',
   'gmai.report_entitlements', 'product_hierarchy',
   'Use product_hierarchy for product/group lookups (searches product_group, product_subgroup, product in one column). Do NOT add the word "Tanzu" in WHERE clauses — all data is implicitly Tanzu.'),
  ('sku',
   'gmai.report_entitlements', 'sku_product_group',
   'For SKU-based queries use sku_product_group FIRST, before product_hierarchy.'),
  ('sales pod',
   'gmai.report_sales_pod_table', 'sales_pod',
   'sales_leader and sa_leader live in gmai.report_sales_pod_table. Always JOIN ON rsp.sales_pod = re.sales_pod before referencing these columns.'),
  ('region',
   'gmai.report_entitlements', 'sales_pod',
   'For regional/territory questions use sales_pod, not the geo column. Ignore geo unless user explicitly asks for it.'),
  ('territory',
   'gmai.report_entitlements', 'sales_pod',
   'Use sales_pod for territory queries, not geo.'),
  ('renewal',
   'gmai.report_entitlements', NULL,
   'Use MIN() for next renewal, MAX() for last renewal on the date/quarter column. NEVER use LIMIT 1 or row-level filtering to find a boundary date.'),
  ('fiscal year',
   'gmai.report_entitlements', 'purchase_qtr',
   'Fiscal year starts the Monday between Oct 29–Nov 4 of the PREVIOUS calendar year. For purchase dates filter using purchase_qtr LIKE ''25%''. For active entitlements use start_qtr/end_qtr range.'),
  ('purchase date',
   'gmai.report_entitlements', 'purchase_qtr',
   'Filter by fiscal year using purchase_qtr LIKE ''25%'' for FY25. Use purchase_date for exact date filters.'),
  ('industry',
   'gmai.report_parent_account_data', 'industry',
   'Industry lives in gmai.report_parent_account_data. JOIN ON rpa.parent_name = re.parent_name AND rpa.asof = re.asof. Only use when user explicitly asks.')
ON CONFLICT (term) DO NOTHING;


-- =============================================================================
-- Seed query templates for common business questions
-- =============================================================================

INSERT INTO query_templates (name, keywords, hint_sql, description) VALUES

  ('Top customers by EAR',
   'top customers, top accounts, largest customers, highest ear, most revenue',
   'SELECT re.parent_name, SUM(re.ear) AS total_ear
FROM gmai.report_entitlements re
WHERE re.is_current = 1
GROUP BY re.parent_name
ORDER BY total_ear DESC
LIMIT {n};',
   'Rank customers by Estimated Annual Revenue'),

  ('Active entitlements count',
   'how many entitlements, count entitlements, active entitlements, total entitlements',
   'SELECT COUNT(*) AS entitlement_count
FROM gmai.report_entitlements
WHERE is_current = 1;',
   'Count of currently active entitlement records'),

  ('Customer count by region / sales pod',
   'customers by region, customers by pod, how many customers, customer count by territory',
   'SELECT re.sales_pod, COUNT(DISTINCT re.parent_name) AS customer_count, SUM(re.ear) AS total_ear
FROM gmai.report_entitlements re
WHERE re.is_current = 1
GROUP BY re.sales_pod
ORDER BY total_ear DESC;',
   'Customer and EAR breakdown per sales pod'),

  ('Renewal pipeline',
   'renewal pipeline, upcoming renewals, expiring, renewals next quarter, renewals this quarter',
   'SELECT re.parent_name, re.end_qtr, SUM(re.ear) AS ear_at_risk
FROM gmai.report_entitlements re
WHERE re.is_current = 1
  AND re.end_qtr = ''{target_quarter}''
GROUP BY re.parent_name, re.end_qtr
ORDER BY ear_at_risk DESC;',
   'Entitlements expiring in a given quarter'),

  ('Top products by EAR',
   'top products, popular products, products by revenue, product breakdown, product mix',
   'SELECT re.product_hierarchy, SUM(re.ear) AS total_ear, COUNT(DISTINCT re.parent_name) AS customers
FROM gmai.report_entitlements re
WHERE re.is_current = 1
GROUP BY re.product_hierarchy
ORDER BY total_ear DESC
LIMIT {n};',
   'Products ranked by Estimated Annual Revenue'),

  ('New logos in a period',
   'new logos, new customers, brand new, first time, net new',
   'SELECT re.parent_name, MIN(re.purchase_date) AS first_purchase, SUM(re.ear) AS ear
FROM gmai.report_entitlements re
WHERE re.purchase_qtr LIKE ''{fy}%''
  AND re.parent_name NOT IN (
      SELECT DISTINCT parent_name FROM gmai.report_entitlements
      WHERE purchase_qtr < ''{fy}Q1''
  )
GROUP BY re.parent_name
ORDER BY ear DESC;',
   'Customers with no prior entitlement before the stated fiscal year'),

  ('EAR by sales pod with leaders',
   'sales leader, sa leader, pod leader, who owns, territory leader',
   'SELECT rsp.sales_pod, rsp.sales_leader, rsp.sa_leader,
       COUNT(DISTINCT re.parent_name) AS customers, SUM(re.ear) AS total_ear
FROM gmai.report_entitlements re
JOIN gmai.report_sales_pod_table rsp ON rsp.sales_pod = re.sales_pod
WHERE re.is_current = 1
GROUP BY rsp.sales_pod, rsp.sales_leader, rsp.sa_leader
ORDER BY total_ear DESC;',
   'EAR and customer count with sales and SA leaders per pod')

ON CONFLICT DO NOTHING;
