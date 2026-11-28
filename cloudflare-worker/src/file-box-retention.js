export function retainFileBoxItems(items) {
  const rows = Array.isArray(items) ? items.filter(item => item && typeof item === "object") : [];
  const timestamp = item => {
    const value = Date.parse(String(item?.createdAt || ""));
    return Number.isFinite(value) ? value : 0;
  };
  // Keep eight items total across uploaded files and generated media, newest first.
  // The caller removes evicted object paths from storage after the Firestore write.
  return rows
    .sort((a, b) => timestamp(b) - timestamp(a))
    .slice(0, 8)
    .sort((a, b) => timestamp(a) - timestamp(b));
}
