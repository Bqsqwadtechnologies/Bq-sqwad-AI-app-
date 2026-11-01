/**
 * Shared AI model catalog contract (Prompt G).
 * Mirrored from website src/lib/ai-model-catalog.ts.
 * Clients never see provider model IDs or prices.
 */

export type ModelKind =
  | "chat"
  | "image"
  | "transcribe"
  | "realtime"
  | "speech"
  | "embedding"
  | "moderation"
  | "other";

export type ModelStatus = "active" | "inactive";

export type AiModelCatalogEntry = {
  modelKey: string;
  providerModelId: string;
  provider: string;
  displayName: string;
  icon: string;
  description: string;
  kind: ModelKind;
  status: ModelStatus;
  sortOrder: number;
  allowFreeCredit: boolean;
  requiresPurchasedCredits: boolean;
  userSelectable: boolean;
  fallbackFor: string[];
  plans: string[];
  tags: string[];
  pricing: {
    inputCreditsPerToken: string;
    cachedInputCreditsPerToken: string;
    outputCreditsPerToken: string;
  };
  version: number;
  updatedAt: string;
};

export type PublicModelProjection = {
  modelKey: string;
  displayName: string;
  icon: string;
  description: string;
  sortOrder: number;
  locked: boolean;
  lockReason: "model_unavailable" | "purchased_credits_required" | "out_of_credits" | null;
};

export const SEED_MODELS: AiModelCatalogEntry[] = [
  {
    modelKey: "bq-7-star",
    providerModelId: "gpt-6-luna",
    provider: "openai",
    displayName: "BQ-7-Star",
    icon: "star-spark",
    description: "Fast everyday intelligence",
    kind: "chat",
    status: "inactive",
    sortOrder: 1,
    allowFreeCredit: true,
    requiresPurchasedCredits: false,
    userSelectable: true,
    fallbackFor: [],
    plans: [],
    tags: ["fast", "intelligence"],
    pricing: {
      inputCreditsPerToken: "0",
      cachedInputCreditsPerToken: "0",
      outputCreditsPerToken: "0",
    },
    version: 1,
    updatedAt: new Date().toISOString(),
  },
  {
    modelKey: "bq-codron",
    providerModelId: "gpt-5.6-luna",
    provider: "openai",
    displayName: "BQ-Codron",
    icon: "code-circuit",
    description: "Coding advice · Intelligence · Ultra fast",
    kind: "chat",
    status: "inactive",
    sortOrder: 2,
    allowFreeCredit: false,
    requiresPurchasedCredits: true,
    userSelectable: true,
    fallbackFor: [],
    plans: [],
    tags: ["coding", "intelligence", "fast"],
    pricing: {
      inputCreditsPerToken: "0",
      cachedInputCreditsPerToken: "0",
      outputCreditsPerToken: "0",
    },
    version: 1,
    updatedAt: new Date().toISOString(),
  },
];

export type CanUseModelResult =
  | { ok: true }
  | { ok: false; reason: "model_unavailable" | "purchased_credits_required" | "out_of_credits" };

export function canUseModel(
  user: { balance: number; hasPurchasedCredits: boolean },
  model: Pick<
    AiModelCatalogEntry,
    "status" | "requiresPurchasedCredits" | "allowFreeCredit"
  >,
): CanUseModelResult {
  if (model.status !== "active") {
    return { ok: false, reason: "model_unavailable" };
  }
  if (model.requiresPurchasedCredits && !user.hasPurchasedCredits) {
    return { ok: false, reason: "purchased_credits_required" };
  }
  if (user.balance <= 0) {
    return { ok: false, reason: "out_of_credits" };
  }
  return { ok: true };
}

export function toPublicProjection(
  models: AiModelCatalogEntry[],
  user: { balance: number; hasPurchasedCredits: boolean },
): PublicModelProjection[] {
  return models
    .filter((m) => m.userSelectable)
    .sort((a, b) => a.sortOrder - b.sortOrder)
    .map((m) => {
      const result = canUseModel(user, m);
      return {
        modelKey: m.modelKey,
        displayName: m.displayName,
        icon: m.icon,
        description: m.description,
        sortOrder: m.sortOrder,
        locked: !result.ok,
        lockReason: result.ok ? null : result.reason,
      };
    });
}
