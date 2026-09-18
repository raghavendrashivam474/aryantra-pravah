# Pravah Protocol — Wire Format Specification

> **Version:** 0x01
> **Status:** S2.2 (Encoder / Parser)
> **Scope:** Logical message ↔ deterministic byte representation

---

## 1. Overview

The Pravah wire format defines how a logical `Message` is represented
as a sequence of bytes. This specification is transport-independent:
it applies equally to TCP, Bluetooth, LAN, or any future transport.

The wire format answers:

> What does a message look like as bytes?

It does **not** answer:

> How are message boundaries detected in a byte stream?

That problem (framing) belongs to S2.3.

### Design Goals

- **Deterministic:** The same logical message always produces the
  identical byte sequence.
- **Unambiguous:** Every byte has a defined meaning; no guessing.
- **Parseable:** A strict parser can reconstruct the original message
  or reject malformed input.
- **Versionable:** A version field allows future evolution without
  breaking existing parsers.
- **Minimal:** No fields exist that are not required by the current
  protocol model.

---

## 2. Message Types

The protocol currently defines three message types. These are
vocabulary entries, not chat behaviors (semantics belong to S2.4).

| Type      | Code   | Purpose (future)                        |
|-----------|--------|-----------------------------------------|
| `JOIN`    | `0x01` | Peer announces presence to the network  |
| `MESSAGE` | `0x02` | Carries application payload between peers |
| `LEAVE`   | `0x03` | Peer announces departure from the network |

Any code not listed above is **unknown** and must be rejected by the
parser. Unknown codes are not silently mapped to existing types.

---

## 3. Logical Message Fields

A `Message` consists of exactly four fields:

| Field        | Type         | Required | Description                                      |
|--------------|--------------|----------|--------------------------------------------------|
| `type`       | MessageType  | Yes      | One of JOIN, MESSAGE, LEAVE                      |
| `senderId`   | String       | Yes      | Logical identity of the originating peer          |
| `messageId`  | String       | Yes      | Unique identifier for this logical message        |
| `payload`    | byte[]       | Yes      | The message content (may be zero-length)          |

### Field Constraints

- `senderId` must not be null or blank.
- `messageId` must not be null or blank.
- `payload` must not be null (zero-length is valid).
- `senderId` UTF-8 byte length must not exceed 65,535 bytes.
- `messageId` UTF-8 byte length must not exceed 65,535 bytes.
- `payload` byte length must not exceed 2,147,483,647 bytes
  (Java `int` max, enforced by the 4-byte length prefix).

### Identity Distinction

TCP connection ID ≠ logical peer ID (senderId) ≠ message ID (messageId)


>These are separate concepts. The `senderId` is a logical protocol identity, not a socket address.


---

## 4. Binary Wire Layout
```
Every encoded message follows this deterministic byte sequence:
0 1 2 3
0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| MAGIC ('PR') | VERSION | TYPE CODE |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| SENDER_ID_LEN (2B) | SENDER_ID (N bytes) ... |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| MESSAGE_ID_LEN (2B) | MESSAGE_ID (M bytes) ... |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| PAYLOAD_LEN (4B) |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
| PAYLOAD (P bytes) ... |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Minimum Message Size

The smallest possible valid message has empty sender, messageId, and payload,
which requires exactly **12 bytes**:
2 (magic) + 1 (version) + 1 (type) + 2 (sender_len) + 2 (msg_len) + 4 (payload_len) = 12 bytes


*(Note: In practice, senderId and messageId cannot be blank per model validation,
so actual messages will always exceed 12 bytes).*

---

## 5. Byte-Level Encoding Rules

1. **Byte Order (Endianness):** Network byte order (Big-Endian) is used for all
   multi-byte integer fields (`short` and `int`).
2. **String Encoding:** All strings (`senderId`, `messageId`) are strictly encoded
   as UTF-8 without byte order marks or null terminators.
3. **Magic Header:** Fixed 2-byte sequence `0x50 0x52` (`ASCII "PR"`).
4. **Protocol Version:** Single byte `0x01` representing v1.
5. **Length Prefixing:** Strings are prefixed with a 2-byte unsigned short (0–65535).
   Payloads are prefixed with a 4-byte signed integer (>= 0).
6. **No Padding:** No alignment padding or trailing filler bytes are added.

---

## 6. Parsing Rules & Malformed Input Handling

The parser strictly enforces format adherence without making assumptions:

| Error Condition | Parser Action | Exception Thrown |
|---|---|---|
| Input is `null` | Reject | `NullPointerException` |
| Total length < 12 bytes | Reject | `ProtocolException("Data is truncated...")` |
| Magic bytes != `PR` | Reject | `ProtocolException("Invalid magic header...")` |
| Version != `0x01` | Reject | `ProtocolException("Unsupported protocol version...")` |
| Unknown message type code | Reject | `ProtocolException("Invalid message type code...")` |
| Truncated field bytes | Reject | `ProtocolException("Failed to parse message...")` |
| Negative payload length | Reject | `ProtocolException("Invalid negative payload length...")` |
| Unparsed trailing bytes | Reject | `ProtocolException("Trailing bytes detected...")` |

---

## 7. Concrete Byte Example

Consider the following logical message:
- **Type:** `MESSAGE` (`0x02`)
- **Sender ID:** `"alice"` (5 bytes)
- **Message ID:** `"m1"` (2 bytes)
- **Payload:** `"Hi"` (`0x48 0x69`, 2 bytes)

### Wire Representation (Hex Dump):

```text
Offset    Hex Bytes                    Meaning
0000      50 52                        Magic: "PR"
0002      01                           Version: 1
0003      02                           Type: MESSAGE
0004      00 05                        Sender ID Length: 5 bytes
0006      61 6c 69 63 65               Sender ID: "alice"
000b      00 02                        Message ID Length: 2 bytes
000d      6d 31                        Message ID: "m1"
000f      00 00 00 02                  Payload Length: 2 bytes
0013      48 69                        Payload: "Hi"
```

>Total size: 21 bytes.