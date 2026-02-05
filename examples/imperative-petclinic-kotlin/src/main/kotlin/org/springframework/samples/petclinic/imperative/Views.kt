package org.springframework.samples.petclinic.imperative

internal fun ownerSearchForm(message: String? = null): String =
    """
    <h1>Find Owners</h1>
    ${message?.let { "<p class=\"error\">$it</p>" } ?: ""}
    <form action="/owners" method="get">
      <label for="lastName">Last name</label>
      <input id="lastName" name="lastName" type="text">
      <button type="submit">Find Owner</button>
    </form>
    <p><a class="button" href="/owners/new">Add Owner</a></p>
    """.trimIndent()

internal fun ownerForm(action: String, owner: OwnerInput, issues: List<FieldIssue> = emptyList()): String =
    """
    <h1>Owner</h1>
    ${issuesHtml(issues)}
    <form action="${escape(action)}" method="post">
      ${input("firstName", "First name", owner.firstName)}
      ${input("lastName", "Last name", owner.lastName)}
      ${input("address", "Address", owner.address)}
      ${input("city", "City", owner.city)}
      ${input("telephone", "Telephone", owner.telephone)}
      <button type="submit">Save Owner</button>
    </form>
    """.trimIndent()

internal fun ownersTable(owners: List<Owner>): String =
    """
    <h1>Owners</h1>
    <table>
      <thead><tr><th>Name</th><th>Address</th><th>City</th><th>Telephone</th><th>Pets</th></tr></thead>
      <tbody>
        ${owners.joinToString("") { owner ->
            "<tr><td><a href=\"/owners/${owner.id}\">${escape(owner.firstName)} ${escape(owner.lastName)}</a></td><td>${escape(owner.address)}</td><td>${escape(owner.city)}</td><td>${escape(owner.telephone)}</td><td>${escape(owner.pets.joinToString { it.name })}</td></tr>"
        }}
      </tbody>
    </table>
    """.trimIndent()

internal fun ownerSummary(owner: Owner): String =
    """
    <h1>${escape(owner.firstName)} ${escape(owner.lastName)}</h1>
    <table>
      <tr><th>Address</th><td>${escape(owner.address)}</td></tr>
      <tr><th>City</th><td>${escape(owner.city)}</td></tr>
      <tr><th>Telephone</th><td>${escape(owner.telephone)}</td></tr>
    </table>
    <p class="actions"><a class="button" href="/owners/${owner.id}/edit">Edit Owner</a><a class="button" href="/owners/${owner.id}/pets/new">Add Pet</a></p>
    <h2>Pets and Visits</h2>
    ${owner.pets.joinToString("") { petSummary(owner.id, it) }.ifBlank { "<p class=\"muted\">No pets.</p>" }}
    """.trimIndent()

private fun petSummary(ownerId: Int, pet: Pet): String =
    """
    <section>
      <h3>${escape(pet.name)}</h3>
      <p>${escape(pet.typeName)} · ${pet.birthDate?.let { escape(it.toString()) } ?: "unknown birth date"}</p>
      <p class="actions"><a href="/owners/$ownerId/pets/${pet.id}/edit">Edit pet</a><a href="/owners/$ownerId/pets/${pet.id}/visits/new">Add visit</a></p>
      <table>
        <thead><tr><th>Visit date</th><th>Description</th></tr></thead>
        <tbody>
          ${pet.visits.joinToString("") { "<tr><td>${escape(it.date?.toString() ?: "")}</td><td>${escape(it.description)}</td></tr>" }.ifBlank { "<tr><td colspan=\"2\" class=\"muted\">No visits.</td></tr>" }}
        </tbody>
      </table>
    </section>
    """.trimIndent()

internal fun petForm(action: String, pet: PetInput, types: List<PetType>, issues: List<FieldIssue> = emptyList()): String =
    """
    <h1>Pet</h1>
    ${issuesHtml(issues)}
    <form action="${escape(action)}" method="post">
      ${input("name", "Name", pet.name)}
      ${input("birthDate", "Birth date", pet.birthDate?.toString() ?: "", "date")}
      <label for="type">Type</label>
      <select id="type" name="type">
        ${types.joinToString("") { type -> "<option value=\"${type.id}\" ${if (type.id == pet.typeId) "selected" else ""}>${escape(type.name)}</option>" }}
      </select>
      <button type="submit">Save Pet</button>
    </form>
    """.trimIndent()

internal fun visitForm(action: String, pet: Pet, visit: VisitInput, issues: List<FieldIssue> = emptyList()): String =
    """
    <h1>Visit for ${escape(pet.name)}</h1>
    ${issuesHtml(issues)}
    <form action="${escape(action)}" method="post">
      ${input("date", "Date", visit.date?.toString() ?: "", "date")}
      ${input("description", "Description", visit.description)}
      <button type="submit">Save Visit</button>
    </form>
    """.trimIndent()

internal fun vetsTable(vets: List<Vet>): String =
    """
    <h1>Veterinarians</h1>
    <p class="actions"><a href="/vets.json">JSON</a><a href="/vets.xml">XML</a></p>
    <table>
      <thead><tr><th>Name</th><th>Specialties</th></tr></thead>
      <tbody>
        ${vets.joinToString("") { vet -> "<tr><td>${escape(vet.firstName)} ${escape(vet.lastName)}</td><td>${escape(vet.specialties.joinToString { it.name }.ifBlank { "none" })}</td></tr>" }}
      </tbody>
    </table>
    """.trimIndent()

private fun input(name: String, label: String, value: String, type: String = "text"): String =
    """
    <label for="${escape(name)}">${escape(label)}</label>
    <input id="${escape(name)}" name="${escape(name)}" type="${escape(type)}" value="${escape(value)}">
    """.trimIndent()

private fun issuesHtml(issues: List<FieldIssue>): String =
    issues.joinToString("") { issue -> "<p class=\"error\">${escape(issue.field)}: ${escape(issue.message)}</p>" }
