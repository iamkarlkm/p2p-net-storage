import protobuf from "protobufjs"

export type IMUserModel = {
  user_id: string
  username?: string
  nickname?: string
  token?: string
  status?: string
  ip?: string
  port?: number
  public_key?: string
  avatar?: string
  signature?: string
  extra?: string
}

export type IMChatModel = {
  msg_id?: string
  sender_id: string
  receiver_id: string
  receiver_type?: string
  msg_type?: string
  content?: string
  timestamp?: number
  extra?: string
  quote_msg_id?: string
  at_users?: string[]
  file_info?: {
    store_id?: number
    length?: number
    data?: Uint8Array
    path?: string
    md5?: string
    block_size?: number
  }
}

export type IMChatAck = {
  msg_id: string
  user_id: string
  timestamp?: number
  ack_type?: string
  peer_id?: string
}

export type IMChatHistoryRequest = {
  user_id: string
  peer_id: string
  limit?: number
}

export type IMChatHistoryResponse = {
  items?: IMChatModel[]
}

export type IMUserListResponse = {
  items?: IMUserModel[]
}

export type IMGroupModel = {
  group_id: string
  name?: string
  owner_id?: string
  avatar?: string
  notice?: string
  extra?: string
  admin_ids?: string[]
}

export type IMGroupListResponse = {
  items?: IMGroupModel[]
}

export type IMGroupListRequest = {
  user_id?: string
}

export type IMGroupMembersResponse = {
  group_id: string
  items?: IMUserModel[]
}

export type IMGroupDismissRequest = {
  group_id: string
  operator_id: string
}

export type IMGroupRemoveMemberRequest = {
  group_id: string
  operator_id: string
  member_id: string
}

export type IMGroupUpdateInfoRequest = {
  group_id: string
  operator_id: string
  name?: string
  avatar?: string
  notice?: string
  extra?: string
}

export type IMGroupSetAdminRequest = {
  group_id: string
  operator_id: string
  member_id: string
  is_admin?: boolean
}

export type IMSystemEvent = {
  type?: string
  group_id?: string
  operator_id?: string
  target_id?: string
  timestamp?: number
  message?: string
}

function encodeType(root: protobuf.Root, fullName: string, obj: unknown): Uint8Array {
  const T = root.lookupType(fullName)
  const msg = T.create(obj as Record<string, unknown>)
  return T.encode(msg).finish()
}

function decodeType(root: protobuf.Root, fullName: string, data: Uint8Array): any {
  const T = root.lookupType(fullName)
  return T.decode(data) as any
}

export function encodeIMUserModel(root: protobuf.Root, m: IMUserModel): Uint8Array {
  return encodeType(root, "p2pws.IMUserModel", m)
}

export function decodeIMUserModel(root: protobuf.Root, data: Uint8Array): IMUserModel {
  const msg = decodeType(root, "p2pws.IMUserModel", data)
  return msg as IMUserModel
}

export function encodeIMChatModel(root: protobuf.Root, m: IMChatModel): Uint8Array {
  return encodeType(root, "p2pws.IMChatModel", m)
}

export function decodeIMChatModel(root: protobuf.Root, data: Uint8Array): IMChatModel {
  const msg = decodeType(root, "p2pws.IMChatModel", data)
  return msg as IMChatModel
}

export function encodeIMChatAck(root: protobuf.Root, m: IMChatAck): Uint8Array {
  return encodeType(root, "p2pws.IMChatAck", m)
}

export function decodeIMChatAck(root: protobuf.Root, data: Uint8Array): IMChatAck {
  const msg = decodeType(root, "p2pws.IMChatAck", data)
  return msg as IMChatAck
}

export function encodeIMChatHistoryRequest(root: protobuf.Root, m: IMChatHistoryRequest): Uint8Array {
  return encodeType(root, "p2pws.IMChatHistoryRequest", m)
}

export function decodeIMChatHistoryRequest(root: protobuf.Root, data: Uint8Array): IMChatHistoryRequest {
  const msg = decodeType(root, "p2pws.IMChatHistoryRequest", data)
  return msg as IMChatHistoryRequest
}

export function encodeIMChatHistoryResponse(root: protobuf.Root, m: IMChatHistoryResponse): Uint8Array {
  return encodeType(root, "p2pws.IMChatHistoryResponse", m)
}

export function decodeIMChatHistoryResponse(root: protobuf.Root, data: Uint8Array): IMChatHistoryResponse {
  const msg = decodeType(root, "p2pws.IMChatHistoryResponse", data)
  return msg as IMChatHistoryResponse
}

export function encodeIMUserListResponse(root: protobuf.Root, m: IMUserListResponse): Uint8Array {
  return encodeType(root, "p2pws.IMUserListResponse", m)
}

export function decodeIMUserListResponse(root: protobuf.Root, data: Uint8Array): IMUserListResponse {
  const msg = decodeType(root, "p2pws.IMUserListResponse", data)
  return msg as IMUserListResponse
}

export function encodeIMGroupModel(root: protobuf.Root, m: IMGroupModel): Uint8Array {
  return encodeType(root, "p2pws.IMGroupModel", m)
}

export function decodeIMGroupModel(root: protobuf.Root, data: Uint8Array): IMGroupModel {
  const msg = decodeType(root, "p2pws.IMGroupModel", data)
  return msg as IMGroupModel
}

export function encodeIMGroupListResponse(root: protobuf.Root, m: IMGroupListResponse): Uint8Array {
  return encodeType(root, "p2pws.IMGroupListResponse", m)
}

export function decodeIMGroupListResponse(root: protobuf.Root, data: Uint8Array): IMGroupListResponse {
  const msg = decodeType(root, "p2pws.IMGroupListResponse", data)
  return msg as IMGroupListResponse
}

export function encodeIMGroupListRequest(root: protobuf.Root, m: IMGroupListRequest): Uint8Array {
  return encodeType(root, "p2pws.IMGroupListRequest", m)
}

export function decodeIMGroupListRequest(root: protobuf.Root, data: Uint8Array): IMGroupListRequest {
  const msg = decodeType(root, "p2pws.IMGroupListRequest", data)
  return msg as IMGroupListRequest
}

export function encodeIMGroupMembersResponse(root: protobuf.Root, m: IMGroupMembersResponse): Uint8Array {
  return encodeType(root, "p2pws.IMGroupMembersResponse", m)
}

export function decodeIMGroupMembersResponse(root: protobuf.Root, data: Uint8Array): IMGroupMembersResponse {
  const msg = decodeType(root, "p2pws.IMGroupMembersResponse", data)
  return msg as IMGroupMembersResponse
}

export function encodeIMGroupDismissRequest(root: protobuf.Root, m: IMGroupDismissRequest): Uint8Array {
  return encodeType(root, "p2pws.IMGroupDismissRequest", m)
}

export function decodeIMGroupDismissRequest(root: protobuf.Root, data: Uint8Array): IMGroupDismissRequest {
  const msg = decodeType(root, "p2pws.IMGroupDismissRequest", data)
  return msg as IMGroupDismissRequest
}

export function encodeIMGroupRemoveMemberRequest(root: protobuf.Root, m: IMGroupRemoveMemberRequest): Uint8Array {
  return encodeType(root, "p2pws.IMGroupRemoveMemberRequest", m)
}

export function decodeIMGroupRemoveMemberRequest(root: protobuf.Root, data: Uint8Array): IMGroupRemoveMemberRequest {
  const msg = decodeType(root, "p2pws.IMGroupRemoveMemberRequest", data)
  return msg as IMGroupRemoveMemberRequest
}

export function encodeIMGroupUpdateInfoRequest(root: protobuf.Root, m: IMGroupUpdateInfoRequest): Uint8Array {
  return encodeType(root, "p2pws.IMGroupUpdateInfoRequest", m)
}

export function decodeIMGroupUpdateInfoRequest(root: protobuf.Root, data: Uint8Array): IMGroupUpdateInfoRequest {
  const msg = decodeType(root, "p2pws.IMGroupUpdateInfoRequest", data)
  return msg as IMGroupUpdateInfoRequest
}

export function encodeIMGroupSetAdminRequest(root: protobuf.Root, m: IMGroupSetAdminRequest): Uint8Array {
  return encodeType(root, "p2pws.IMGroupSetAdminRequest", m)
}

export function decodeIMGroupSetAdminRequest(root: protobuf.Root, data: Uint8Array): IMGroupSetAdminRequest {
  const msg = decodeType(root, "p2pws.IMGroupSetAdminRequest", data)
  return msg as IMGroupSetAdminRequest
}

export function encodeIMSystemEvent(root: protobuf.Root, m: IMSystemEvent): Uint8Array {
  return encodeType(root, "p2pws.IMSystemEvent", m)
}

export function decodeIMSystemEvent(root: protobuf.Root, data: Uint8Array): IMSystemEvent {
  const msg = decodeType(root, "p2pws.IMSystemEvent", data)
  return msg as IMSystemEvent
}
