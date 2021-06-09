---
title: Old Roles Reference
description: Detailed explanation of the SQLAlchemy built-in roles features and how to use them.
aliases:
  - ../../getting-started/roles/index.html
any: true
weight: 3
---

# Built-in Roles with SQLAlchemy

{{% callout "Depreciated" "orange" %}}

This guide covers our old implementation of roles. It is still available as `roles_old` but will be removed in a future update.

We have released a new version of our roles library for SQLAlchemy.

[Check it out here!](/new-roles)

{{% /callout %}}

Oso includes support for adding roles directly to your application via our ORM
integrations. These features let you declaratively create models to represent
roles in your application, relate these role models to your user and resource
models, and manage them through helper methods. We also generate Polar rules
that you can use in your Oso policies to write rules based on the roles you
defined, instead of writing rules over users directly.

This feature is currently supported in [the SQLAlchemy library]({{< relref
path="reference/frameworks/sqlalchemy" lang="python" >}}). If you want to get started with
SQLAlchemy roles right away, check out [our tutorial](sqlalchemy_roles).

For a more in-depth understanding of roles, check out our guide to [Role-Based
Access Control (RBAC) patterns](learn/roles).

## Data model

Lets say you have a `User` class and a `Widget` class and you want to assign
users a role for a widget like “OWNER” or “USER”. Using the roles library you
can generate a `WidgetRole` model which allows you to assign a user a role for
a `Widget`. The schema for this new model’s table looks like this:

![Schema diagram showing a `WidgetRole` relating a `User` and a
`Widget`](img/roles.svg)

The `WidgetRole` table is a join table between `User` and `Widget` that
contains additional `id` (Integer) and `name` (String) attributes.

In the SQLAlchemy library we add `User.widgets` and `Widget.users` as
relationships you can query, as well as `User.widget_roles` and `Widget.roles`
to get the roles directly.

{{% callout "Note" "blue" %}}
The names of the relationships we add to models are generated by the
following conventions:

- `Widget.users`: this relationship is always called `users`, regardless of
  the actual user model name
- `User.widgets`: `Widget.__name__ + "s"`
- `Widget.roles`: this relationship is always called `roles`
- `User.widget_roles`: `Widget.__name__.lower() + "_roles"`
  {{% /callout %}}

The library also provides [`helper methods`](https://docs.osohq.com/python/reference/api/sqlalchemy.html#sqlalchemy-oso-roles) for
inspecting and managing roles.

## Base policy

<!-- TODO(gj): link API docs once they're set up. -->

With `WidgetRole` defined, you can call `sqlalchemy_oso.roles.enable_roles()`
which unlocks a few special Polar rules by loading the following base policy:

```polar
# RBAC BASE POLICY

## Top-level RBAC allow rule

### The association between the resource roles and the requested resource is
### outsourced from the rbac_allow
allow(user, action, resource) if
    resource_role_applies_to(resource, role_resource) and
    user_in_role(user, role, role_resource) and
    role_allow(role, action, resource);

# RESOURCE-ROLE RELATIONSHIPS

## These rules allow roles to apply to resources other than those that they are
## scoped to. The most common example of this is nested resources, e.g.,
## Repository roles should apply to the Issues nested in that repository.

### A resource's roles apply to itself
resource_role_applies_to(role_resource, role_resource);

# ROLE-ROLE RELATIONSHIPS

## Role Hierarchies

### Grant a role permissions that it inherits from a more junior role
role_allow(role, action, resource) if
    inherits_role(role, inherited_role) and
    role_allow(inherited_role, action, resource);

### Determine role inheritance based on the `widget_role_order` rule
inherits_role(role: WidgetRole, inherited_role) if
    widget_role_order(role_order) and
    inherits_role_helper(role.name, inherited_role_name, role_order) and
    inherited_role = new WidgetRole(name: inherited_role_name, widget: role.widget);

### Helper to determine relative order or roles in a list
inherits_role_helper(role, inherited_role, role_order) if
    ([first, *rest] = role_order and
    role = first and
    inherited_role in rest) or
    ([first, *rest] = role_order and
    inherits_role_helper(role, inherited_role, rest));

# USER-ROLE RELATIONSHIPS

### Get a user's roles for a specific resource
user_in_role(user: User, role, resource: Widget) if
    session = OsoSession.get() and
    role in session.query(WidgetRole).filter_by(user: user) and
    role.widget.id = resource.id;
```

<!-- TODO(gj): link API docs once they're set up. -->

{{% callout "Warning" "orange" %}}
The roles base policy loaded by `sqlalchemy_oso.roles.enable_roles()` calls
builtin rules with the following name/arity: `user_in_role/3`,
`inherits_role/2`, and `inherits_role_helper/3`. Defining your own rules with
the same name/arity may cause unexpected behavior.
{{% /callout %}}

## Specialized rules

With the roles base policy loaded, you can use the following specialized rules
to write policies over your roles:

## Role-Specific Rules

| Rule name                                                        | Description                                                                                 |
| ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `role_allow(role, action, resource)`                             | allows actors that have `role` to take `action` on `resource`.                              |
| `resource_role_applies_to(child_resource, parent_resource)`      | permits roles that control access to `parent_resource` to apply to `child_resource` as well |
| `[resource_name]_role_order(["ROLE_NAME_1", "ROLE_NAME_2",...])` | specifies a hierarchical role order                                                         |

### role_allow

`role_allow(role, action, resource)` is very similar to the standard `allow`
rule, but instead of an `actor` it takes a `role` as the first argument. The
rule allows actors that have `role` to take `action` on `resource`.

`role` is a SQLAlchemy role model generated by

<!-- TODO(gj): link API docs once they're set up. -->

`sqlalchemy_oso.roles.resource_role_class()`. `resource` is a SQLAlchemy model
to which the `role` applies. Roles apply to the resources that they are scoped
to by default. For example, `OrganizationRole` roles apply to `Organization`
resources. E.g.:

```polar
role_allow(_role: WidgetRole{name: "OWNER"}, "UPDATE", _resource: Widget{});
```

### resource_role_applies_to

`resource_role_applies_to(child_resource, parent_resource)` permits roles that
control access to `parent_resource` to apply to `child_resource` as well.
`parent_resource` must be a resource that has a role class associated with it

<!-- TODO(gj): link API docs once they're set up. -->

(see `sqlalchemy_oso.roles.resource_role_class()`). For instance if you had an
Organization model and wanted people who are admins for the organization to be
able to “UPDATE” all roles, you could do that with the following rules:

```polar
resource_role_applies_to(widget: Widget, org: Organization) if
    widget.organization_id = org.id;

role_allow(_role: OrganizationRole{name: "ADMIN"}, "UPDATE", _resource: Widget{});
```

### [resource_name]\_role_order

`[resource_name]_role_order(["ROLE_NAME_1", "ROLE_NAME_2",...])` specifies a
hierarchical role order for roles defined with

<!-- TODO(gj): link API docs once they're set up. -->

`sqlalchemy_oso.roles.resource_role_class()`. The rule name is the lower-cased
resource model name followed by `_role_order`.

The only parameter is a list of role names in hierarchical order. Roles to the
left will inherit the permissions of roles to the right. This is useful if any
role should inherit all the permissions of another role. It is not required for
all role choices to be specified in the list. For example, to allow
`Organization` admins to do everything that members can do, you could write the
following rule:

```polar
organization_role_order(["ADMIN", "MEMBER"])
```