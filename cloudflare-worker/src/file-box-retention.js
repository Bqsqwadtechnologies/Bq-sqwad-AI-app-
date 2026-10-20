export function retainFileBoxItems(items) {
  const rows = Array.isArray(items) ? items : [];
  // Binary uploads store only small metadata in this document; text snippets
  // carry their content inline, so keep the newest 20 inline text items while
  // preserving up to 200 uploaded-file records across app reinstalls/devices.
  const binary = rows.filter(item => Boolean(item?.objectPath)).slice(-200);
  const textItems = rows.filter(item => !item?.objectPath).slice(-20);
  return [...binary, ...textItems].sort((a, b) => Date.parse(String(a.createdAt || "")) - Date.parse(String(b.createdAt || "")));
}