import { Alert, App as AntApp, Button, Form, Input, Modal, Select, Space, Switch, Table, Tag } from 'antd'
import { useState } from 'react'
import { PageEmpty, PageError, PageLoading } from '../../components/PageState'
import { ROLE_LABEL } from '../auth/roles'
import type { Role } from '../auth/types'
import { AdminShell } from './AdminShell'
import { ALL_ROLES, errorText } from './adminModel'
import { type AdminUser, type CreateUserInput, useAdminUsers, useCreateUser, useReplaceRoles, useSetEnabled } from './adminApi'

const roleOptions = ALL_ROLES.map((r) => ({ value: r, label: ROLE_LABEL[r] }))

function RolesEditor({ user, onDone }: { user: AdminUser; onDone: () => void }) {
  const [roles, setRoles] = useState<Role[]>(user.roles)
  const save = useReplaceRoles()
  return (
    <Modal
      open
      title={`Roles of ${user.name}`}
      okText="Save roles"
      okButtonProps={{ disabled: roles.length === 0, loading: save.isPending }}
      onCancel={onDone}
      onOk={() => save.mutate({ id: user.id, roles }, { onSuccess: onDone })}
    >
      {save.isError ? <Alert type="error" showIcon message={errorText(save.error)} style={{ marginBottom: 12 }} /> : null}
      <label htmlFor={`roles-${user.id}`}>Roles</label>
      <Select
        id={`roles-${user.id}`}
        aria-label="Roles"
        mode="multiple"
        style={{ width: '100%' }}
        value={roles}
        options={roleOptions}
        onChange={setRoles}
      />
    </Modal>
  )
}

function CreateUserModal({ onDone }: { onDone: () => void }) {
  const [form] = Form.useForm<CreateUserInput>()
  const create = useCreateUser()
  return (
    <Modal
      open
      title="Create staff user"
      okText="Create user"
      okButtonProps={{ loading: create.isPending }}
      onCancel={onDone}
      onOk={() => form.validateFields().then((v) => create.mutate(v, { onSuccess: onDone }))}
    >
      {create.isError ? <Alert type="error" showIcon message={errorText(create.error)} style={{ marginBottom: 12 }} /> : null}
      <Form form={form} layout="vertical" initialValues={{ roles: ['EMPLOYEE'] }}>
        <Form.Item name="name" label="Name" rules={[{ required: true, min: 2, max: 100 }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="email" label="Email" rules={[{ required: true, type: 'email' }]}>
          <Input autoComplete="off" />
        </Form.Item>
        <Form.Item name="password" label="Temporary password" rules={[{ required: true, min: 8 }]}>
          <Input.Password autoComplete="new-password" />
        </Form.Item>
        <Form.Item name="roles" label="Roles" rules={[{ required: true, type: 'array', min: 1 }]}>
          <Select mode="multiple" options={roleOptions} />
        </Form.Item>
      </Form>
    </Modal>
  )
}

/** /admin/users (FR-15): search, role filter, create, roles, enable/disable. */
export function AdminUsersPage() {
  const { message } = AntApp.useApp()
  const [q, setQ] = useState('')
  const [role, setRole] = useState('')
  const [page, setPage] = useState(0)
  const [editing, setEditing] = useState<AdminUser | null>(null)
  const [creating, setCreating] = useState(false)
  const users = useAdminUsers(q, role, page)
  const enable = useSetEnabled()

  const filters = (
    <Space wrap>
      <Input.Search
        aria-label="Search users"
        placeholder="Search name or email"
        allowClear
        onSearch={(v) => {
          setQ(v.trim())
          setPage(0)
        }}
        style={{ width: 240 }}
      />
      <Select
        aria-label="Filter by role"
        value={role}
        style={{ width: 180 }}
        onChange={(v) => {
          setRole(v)
          setPage(0)
        }}
        options={[{ value: '', label: 'All roles' }, ...roleOptions]}
      />
      <Button type="primary" onClick={() => setCreating(true)}>
        Create staff user
      </Button>
    </Space>
  )

  let body
  if (users.isPending) body = <PageLoading label="Loading users…" />
  else if (users.isError) body = <PageError message={errorText(users.error)} onRetry={() => void users.refetch()} />
  else if (users.data.items.length === 0) body = <PageEmpty description="No users match these filters" />
  else
    body = (
      <Table<AdminUser>
        rowKey="id"
        dataSource={users.data.items}
        scroll={{ x: 720 }}
        pagination={{ current: page + 1, pageSize: users.data.size, total: users.data.total, onChange: (p) => setPage(p - 1), showSizeChanger: false }}
        columns={[
          { title: 'Name', dataIndex: 'name' },
          { title: 'Email', dataIndex: 'email' },
          { title: 'Department', render: (_, u) => u.department?.name ?? '-' },
          {
            title: 'Roles',
            render: (_, u) => (
              <Space size={[4, 4]} wrap>
                {u.roles.map((r) => (
                  <Tag key={r}>{ROLE_LABEL[r] ?? r}</Tag>
                ))}
              </Space>
            ),
          },
          {
            title: 'Enabled',
            render: (_, u) => (
              <Switch
                aria-label={`Enabled: ${u.name}`}
                checked={u.enabled}
                loading={enable.isPending && enable.variables?.id === u.id}
                onChange={(enabled) =>
                  enable.mutate({ id: u.id, enabled }, { onError: (e) => void message.error(errorText(e)) })
                }
              />
            ),
          },
          {
            title: 'Actions',
            render: (_, u) => (
              <Button size="small" onClick={() => setEditing(u)} aria-label={`Edit roles of ${u.name}`}>
                Edit roles
              </Button>
            ),
          },
        ]}
      />
    )

  return (
    <AdminShell title="Users" extra={filters}>
      {body}
      {editing ? <RolesEditor user={editing} onDone={() => setEditing(null)} /> : null}
      {creating ? <CreateUserModal onDone={() => setCreating(false)} /> : null}
    </AdminShell>
  )
}
