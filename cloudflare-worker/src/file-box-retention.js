export function retainFileBoxItems(items) {
  const rows = Array.isArray(items) ? items : [];
  const newestFirst = (a, b) => Date.parse(String(b?.createdAt || "")) - Date.parse(String(a?.createdAt || ""));
  // Keep the newest records by timestamp, not by whatever order an older
  // document happened to store them in.
  const binary = rows.filter(item => Boolean(item?.objectPath)).sort(newestFirst).slice(0, 200);
  const textItems = rows.filter(item => !item?.objectPath).sort(newestFirst).slice(0, 20);
  return [...binary, ...textItems].sort((a, b) => Date.parse(String(a.createdAt || "")) - Date.parse(String(b.createdAt || ""));
}