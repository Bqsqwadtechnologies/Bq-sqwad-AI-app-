export async function recoverStreamFailure({
  deliveredChars = 0,
  reverseCredits,
  notifyAdmin,
  providerId = null,
  modelId = null,
  safeError = "service_temporarily_unavailable"
}) {
  await Promise.resolve().then(() => reverseCredits?.()).catch(() => {});
  await Promise.resolve().then(() => notifyAdmin?.()).catch(() => {});
  if (Number(deliveredChars) > 0) {
    return {
      type: "completed",
      providerId,
      modelId,
      creditsCharged: 0,
      settlement: "failed_after_delivery"
    };
  }
  return { type: "error", message: safeError };
}
