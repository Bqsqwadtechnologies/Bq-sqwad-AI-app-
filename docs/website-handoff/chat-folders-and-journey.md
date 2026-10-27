# Website handoff: Chat Folders + Chat Journey

## Chat Folders (data model — canonical on website Firestore)

```
users/{uid}/chatFolders/{folderId}:
  name, icon?, color?, sortIndex, pinned, archived, createdAt, updatedAt,
  instructions?   // optional phase 2

chats/{chatId}:
  folderId: string | null
  folderSortIndex: number
```

API (native, Firebase ID token, owner-scoped):
- GET /api/native/chat-folders
- POST /api/native/chat-folders
- PATCH /api/native/chat-folders/:id
- DELETE /api/native/chat-folders/:id  (moves chats to Unfiled)
- POST /api/native/chats/:id/folder
- batch reorder endpoint

Limits: 50 folders/user, name 1–40 chars, unique case-insensitive.
Deleting a folder never deletes chats.

## Chat Journey (stored on chat document)

```
chats/{chatId}.journey = {
  version: 1, updatedAt, upToMessageId,
  nodes: [{ id, messageId, title, summary, kind, parentId, createdAt }],
  openThreads: [{ id, nodeId, text, status, source }]
}
```

Generation: on-demand, cheapest text model, token-billed, incremental by upToMessageId.
Offline: local heuristic outline labelled as such.

## Acceptance tests
- create/rename/delete/reorder folders
- assign/clear chat folder
- delete folder keeps chats in Unfiled
- cross-client sync
- permission isolation
- Journey node order, jump-to-message, cost accounting
