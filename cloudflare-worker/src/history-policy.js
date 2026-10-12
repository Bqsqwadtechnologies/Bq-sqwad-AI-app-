const MAX_HISTORY_MESSAGES = 100;
const MAX_MESSAGE_CHARS = 20000;
const MAX_ATTACHMENTS_PER_MESSAGE = 8;
const MAX_ATTACHMENT_NAME_CHARS = 300;
const MAX_IMAGE_URL_CHARS = 2000;
const MAX_HISTORY_BYTES = 700000;

function fail(code, status = 413) {
  throw Object.assign(new Error(code), { httpStatus: status });
}

export function normalizeHistoryMessages(rawMessages) {
  if (!Array.isArray(rawMessages)) fail("history_messages_required", 400);
  if (rawMessages.length > MAX_HISTORY_MESSAGES) fail("history_message_limit_exceeded");
  const messages = rawMessages.map(item => {
    const text = String(item?.text || "");
    if (text.length > MAX_MESSAGE_CHARS) fail("history_message_too_large");
    const prompt = item?.prompt == null ? null : String(item.prompt);
    if (prompt != null && prompt.length > MAX_MESSAGE_CHARS) fail("history_prompt_too_large");
    const rawAttachments = Array.isArray(item?.attachments) ? item.attachments : [];
    if (rawAttachments.length > MAX_ATTACHMENTS_PER_MESSAGE) fail("history_attachment_limit_exceeded");
    const attachments = rawAttachments.map(value => String(value || ""));
    if (attachments.some(value => value.length > MAX_ATTACHMENT_NAME_CHARS)) fail("history_attachment_name_too_large");
    const imageUrl = typeof item?.imageUrl === "string" ? item.imageUrl : "";
    if (imageUrl && (imageUrl.length > MAX_IMAGE_URL_CHARS || !/^https:\/\//i.test(imageUrl))) {
      fail("history_image_url_invalid", 400);
    }
    const id = String(item?.id || crypto.randomUUID());
    if (id.length > 120) fail("history_message_id_invalid", 400);
    return {
      id,
      mine: item?.mine === true,
      text,
      ...(prompt ? { prompt } : {}),
      ...(attachments.length ? { attachments: attachments.filter(Boolean) } : {}),
      ...(imageUrl ? { imageUrl } : {})
    };
  }).filter(item => item.text.length > 0);
  if (!messages.length) fail("history_messages_required", 400);
  const bytes = new TextEncoder().encode(JSON.stringify(messages)).length;
  if (bytes > MAX_HISTORY_BYTES) fail("history_payload_too_large");
  return messages;
}
