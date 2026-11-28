export function retainFileBoxItems(items) {
  const rows = Array.isArray(items) ? items : [];
  const newestFirst = (a, b) => Date.parse(String(b?.createdAt || "")) - Date.parse(String(a?.createdAt || ""));
  // Keep the 8 newest records by timestamp, automatically evicting older ones.
  const binary = rows.filter(item => Boolean(item?.objectPath)).sort(newestFirst).slice(0, 8);
  const textItems = rows.filter(item => !item?.objectPath).sort(newestFirst).slice(0, 8);
  return [...binary, ...textItems].sort((a, b) => Date.parse(String(a.createdAt || "")) - Date.parse(String(b.createdAt || "")));
}
