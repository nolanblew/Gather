# Gather organization: copy reference

Date: 2026-11-16
Status: Proposed canonical user-facing wording for the organization feature. [Progressive disclosure](ORGANIZATION-DISCLOSURE.md) is the current interaction direction. This reference supersedes conflicting UI wording in earlier proposals; it does not change the data model.

Use sentence case. Show helpers only where specified or when needed to explain an unusual state. Text in `{braces}` is replaced with a user-provided value or count; use appropriate singular and plural forms. Technical documentation may continue to use terms such as recipient, assignment, completion, and filter dimension; these are not automatically UI labels.

## Everyday list and item

| Where | Copy |
| --- | --- |
| New list | **List name**, **Create**, **More options** |
| Empty list | **Add your first item.** |
| Quick entry | **Add item**, **Add** |
| Item editor | **Item**, **Description**, **Add description**, **Add detail**, **Save**, **Cancel** |
| List details | **List details**, **Description**, **Add description**, **Save**, **Cancel** |
| Description preview | **Show more**, **Show less** |
| Item detail display action | **Show item details**, **Hide item details** |
| Overflow into full details | **More details** |

A description is optional, supports multiple paragraphs, and can stand alone or accompany other details. Item and list descriptions are independent. Do not prompt for unused details on the list or pre-open empty fields in the item editor.

## Add detail

The picker contains the applicable choices below, in a stable order. Omit a choice already present on the item. Place or For someone may appear first when used elsewhere in this list; do not open its empty field automatically.

| Choice | Helper or editor copy |
| --- | --- |
| **Description** | Multiline text; **Add description** when empty |
| **Place** | **Places** when displaying selections; **Add place** to add another |
| **For someone** | **Who this item is for** |
| **Quantity** | **Quantity** |
| **Section** | **Section** |

If the picker grows beyond six supported choices, the additional group is **More details**; its search field is **Search details**. Do not display choices for unsupported future features.

## Places and people

| Where | Copy |
| --- | --- |
| Place picker | **Choose places**, **Search places**, **Create “{name}”**, **Done** |
| Place helper | **Any one of these places works.** |
| Explicit place-independent choice | **Anywhere** |
| No place assigned | **No places set** |
| Remove a selected place | **Remove {place}** |
| Person picker | **For someone**, **Search people**, **Create “{name}”**, **Remove person**, **Done** |
| Remove a person | **Remove {person}** |
| Example row | **For Maya · Tokyo or Kyoto** |
| Example row, unrestricted place | **For Maya · Anywhere** |
| Alternative shown under a place filter | **Also Tokyo** |

An item can name one person and several alternative places. Choosing a person does not share the list, invite or notify them. Use **Remove person** to clear the item's person; it does not change its other details. **Anywhere** is an explicit choice and excludes named places; **No places set** means none has been chosen. **Done** returns to the item draft; only **Save** saves it. **Cancel** discards the item changes and any new names created in that draft.

## Filters

| Where | Copy |
| --- | --- |
| List action | **Filter this list** |
| Filter sheet | **Filters**, **Apply**, **Cancel** |
| Completion | **All items**, **Not done** |
| Place filter | **Places**, **All places**, **Anywhere only**, **No places set** |
| Person filter | **For someone**, **No filter**, **Not set** |
| Active filter summary | **Kyoto · Maya · Not done · 3 shown**, **Clear** |
| Unplanned place count | **{count} without a place** |
| Empty filtered results | **No items match these filters.**, **Clear filters** |
| Item saved outside current filter | **Item saved. It isn't shown with these filters.**, **View item** |
| Deleted active place or person | **{name} was removed. The filter was cleared.** |

**Not done** means unchecked items; it appears only for a checklist with completion history. **All items** includes checked and unchecked items. **No filter** includes items with and without a person; **Not set** shows items without one. A named place filter includes items marked **Anywhere**; place choices on an item are alternatives, not separate tasks. Place and person filters appear only when relevant to the current list or needed to recover an active filter. Keep an active summary and **Clear** available even with no results. **Apply** changes the personal view; **Cancel** leaves it unchanged.

## Numbering and order

| Where | Copy |
| --- | --- |
| List action | **Number items** |
| Shared-setting explanation, when needed | **Everyone who can see this list sees item numbers.** |
| Number gaps in filtered view | **Numbers follow the full list order.** |
| Change order from a filtered view | **Edit order**, **Show all items to edit their order.** |
| Accessible ordering | **Move up**, **Move down** |

Numbering is a shared setting controlled by the list owner; filters and item-detail visibility are personal. Numbering works for checklists and notes. Do not describe numbers as permanent IDs or as a new list type.

## Duplicate and remove

| Where | Copy |
| --- | --- |
| General action | **Duplicate item** |
| Person-specific action | **Duplicate for someone else** |
| Duplicate draft prompt | **Who is this item for?** |
| Delete place confirmation | **Delete {place}?** / **It will be removed from {count} item(s). Items and other places will stay.** / **Delete place** / **Cancel** |
| Delete person confirmation | **Delete {person}?** / **They will be removed from {count} item(s). Items will stay.** / **Delete person** / **Cancel** |

Render “1 item” or “{count} items,” never the literal `item(s)`. Duplicates are drafts until saved; their completion is cleared. Deleting a place or person leaves the items and their completion intact.

## Validation and connection

| Situation | Copy |
| --- | --- |
| Missing item text | **Enter an item.** |
| Missing list name | **Enter a list name.** |
| Missing place name | **Enter a place name.** |
| Missing person name | **Enter a name.** |
| Duplicate place | **This place already exists. Choose it instead.** |
| Duplicate person | **This name already exists. Choose it instead.** |
| Save definitely failed | **Couldn't save changes. Try again.** |
| Save outcome uncertain | **Could not confirm the save. Check the list before trying again.** |
| Connection lost while editing | **You're offline. Reconnect to save changes.** |
| Stale shared order | **The list order changed. Review the latest order and try again.** |
| Selected value no longer exists | **{name} is no longer available. Choose another or remove it.** |

Keep the user's draft when saving fails or another edit conflicts. When offline, current in-memory content and local filters remain available; edits require reconnection. Do not imply that a restart will preserve an offline draft.

## Terminology decisions

- **Not done** replaces “Remaining”: it directly describes unchecked items and avoids confusing a count with a filter. **All items** identifies the other completion choice.
- **For someone**, **No filter**, and **Not set** replace “recipient,” “all recipients,” and “not assigned” in controls. They distinguish the person an item is for from someone who has access or must do a task.
- **Place** accommodates shops, cities, home, work, and online services without implying a GPS location. **Any one of these places works** makes the alternative-place meaning explicit.
- **Filter this list** names the action; **Number items** is separate because it changes a shared setting. Avoid the overloaded “View” panel terminology in the current UI.
- **Description** replaces “note” for the editable prose field in this feature. Notes as a list kind remain distinct. Do not add automatic interpretation or AI claims to this manual flow.
