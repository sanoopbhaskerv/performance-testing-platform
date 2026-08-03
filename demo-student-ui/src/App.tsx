import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { createStudent, getStudent, listStudents, updateStudent } from './api'
import type { Student, StudentInput } from './api'
import './App.css'

const emptyStudent: StudentInput = {
  firstName: '',
  lastName: '',
  email: '',
  program: '',
}

function toInput(student: Student): StudentInput {
  const { id: _id, ...studentInput } = student
  return studentInput
}

function StudentFields({
  value,
  onChange,
  idPrefix,
}: {
  value: StudentInput
  onChange: (student: StudentInput) => void
  idPrefix: string
}) {
  const updateField = (field: keyof StudentInput, fieldValue: string) => {
    onChange({ ...value, [field]: fieldValue })
  }

  return (
    <div className="field-grid">
      <label htmlFor={`${idPrefix}-first-name`}>
        First name
        <input
          id={`${idPrefix}-first-name`}
          value={value.firstName}
          onChange={(event) => updateField('firstName', event.target.value)}
          required
          maxLength={80}
        />
      </label>
      <label htmlFor={`${idPrefix}-last-name`}>
        Last name
        <input
          id={`${idPrefix}-last-name`}
          value={value.lastName}
          onChange={(event) => updateField('lastName', event.target.value)}
          required
          maxLength={80}
        />
      </label>
      <label htmlFor={`${idPrefix}-email`}>
        Email
        <input
          id={`${idPrefix}-email`}
          type="email"
          value={value.email}
          onChange={(event) => updateField('email', event.target.value)}
          required
          maxLength={254}
        />
      </label>
      <label htmlFor={`${idPrefix}-program`}>
        Program
        <input
          id={`${idPrefix}-program`}
          value={value.program}
          onChange={(event) => updateField('program', event.target.value)}
          required
          maxLength={120}
        />
      </label>
    </div>
  )
}

function App() {
  const [students, setStudents] = useState<Student[]>([])
  const [isLoadingStudents, setIsLoadingStudents] = useState(false)
  const [createForm, setCreateForm] = useState<StudentInput>(emptyStudent)
  const [activeStudent, setActiveStudent] = useState<Student | null>(null)
  const [detailForm, setDetailForm] = useState<StudentInput>(emptyStudent)
  const [view, setView] = useState<'list' | 'details'>('list')
  const [isEditing, setIsEditing] = useState(false)
  const [message, setMessage] = useState('Service not contacted yet.')
  const [messageLevel, setMessageLevel] = useState<'info' | 'success' | 'error'>('info')
  const [isCreating, setIsCreating] = useState(false)
  const [isUpdating, setIsUpdating] = useState(false)

  const setFeedback = (level: 'info' | 'success' | 'error', nextMessage: string) => {
    setMessageLevel(level)
    setMessage(nextMessage)
  }

  const setStudent = (student: Student, navigateToDetails = false) => {
    setActiveStudent(student)
    setDetailForm(toInput(student))
    if (navigateToDetails) {
      setView('details')
    }
  }

  const loadStudents = async () => {
    try {
      setIsLoadingStudents(true)
      const listedStudents = await listStudents()
      setStudents(listedStudents)
    } catch (error) {
      setFeedback('error', error instanceof Error ? error.message : 'Unable to list students.')
    } finally {
      setIsLoadingStudents(false)
    }
  }

  useEffect(() => {
    loadStudents()
  }, [])

  const handleCreate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    try {
      setIsCreating(true)
      const student = await createStudent(createForm)
      setStudent(student, true)
      setStudents((previous) => [...previous, student].sort((a, b) => a.id - b.id))
      setCreateForm(emptyStudent)
      setFeedback('success', `Student #${student.id} was created.`)
      setIsEditing(false)
    } catch (error) {
      setFeedback('error', error instanceof Error ? error.message : 'Unable to create the student.')
    } finally {
      setIsCreating(false)
    }
  }

  const openStudentDetails = async (studentId: number) => {
    try {
      const student = await getStudent(studentId)
      setStudent(student, true)
      setIsEditing(false)
      setFeedback('success', `Student #${student.id} was retrieved.`)
    } catch (error) {
      setActiveStudent(null)
      setFeedback('error', error instanceof Error ? error.message : 'Unable to retrieve the student.')
    }
  }

  const handleUpdate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!activeStudent) {
      setFeedback('error', 'Retrieve a student before saving changes.')
      return
    }

    try {
      setIsUpdating(true)
      const student = await updateStudent(activeStudent.id, detailForm)
      setStudent(student)
      setStudents((previous) => previous.map((entry) => (entry.id === student.id ? student : entry)))
      setFeedback('success', `Student #${student.id} was updated.`)
      setIsEditing(false)
    } catch (error) {
      setFeedback('error', error instanceof Error ? error.message : 'Unable to update the student.')
    } finally {
      setIsUpdating(false)
    }
  }

  const handleBackToList = () => {
    setView('list')
    setIsEditing(false)
  }

  return (
    <main className="workspace">
      <header className="masthead">
        <div>
          <p className="eyebrow">Demo service client</p>
          <h1>Student Registry</h1>
        </div>
        <p className="service-status"><span aria-hidden="true"></span> API at localhost:8081</p>
      </header>

      <section className={`summary summary-${messageLevel}`} aria-live="polite">
        <p className="summary-label">Latest response</p>
        <p>{message}</p>
      </section>

      {view === 'list' && (
        <section className="work-area" aria-label="Student list and creation">
          <section className="operation list-operation" aria-labelledby="student-list-title">
            <div className="operation-heading">
              <p className="endpoint">GET /api/students</p>
              <h2 id="student-list-title">Student list</h2>
            </div>
            {isLoadingStudents ? (
              <p className="empty-state">Loading students...</p>
            ) : students.length === 0 ? (
              <p className="empty-state">No students yet. Create one from the panel on the right.</p>
            ) : (
              <ul className="student-list" aria-label="Students">
                {students.map((student) => (
                  <li key={student.id}>
                    <button
                      type="button"
                      className="student-list-item"
                      onClick={() => openStudentDetails(student.id)}
                    >
                      <span className="student-name">{student.firstName} {student.lastName}</span>
                      <span className="student-subtext">#{student.id} · {student.program}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <form className="operation create-operation" onSubmit={handleCreate}>
            <div className="operation-heading">
              <p className="endpoint">POST /api/students</p>
              <h2>Add a student</h2>
            </div>
            <StudentFields value={createForm} onChange={setCreateForm} idPrefix="create" />
            <button type="submit" disabled={isCreating}>{isCreating ? 'Creating...' : 'Create student'}</button>
          </form>
        </section>
      )}

      {view === 'details' && activeStudent && (
        <section className="work-area" aria-label="Student details and edit">
          <section className="active-record details-card" aria-labelledby="active-record-title">
            <div className="operation-heading">
              <p className="endpoint">GET /api/students/{'{id}'}</p>
              <h2 id="active-record-title">Student details</h2>
            </div>
            <dl>
              <div><dt>ID</dt><dd>{activeStudent.id}</dd></div>
              <div><dt>Name</dt><dd>{activeStudent.firstName} {activeStudent.lastName}</dd></div>
              <div><dt>Email</dt><dd>{activeStudent.email}</dd></div>
              <div><dt>Program</dt><dd>{activeStudent.program}</dd></div>
            </dl>
            <div className="details-actions">
              <button type="button" onClick={handleBackToList}>Back to list</button>
              <button type="button" onClick={() => setIsEditing((current) => !current)}>
                {isEditing ? 'Cancel edit' : 'Edit student'}
              </button>
            </div>
          </section>

          {isEditing && (
            <form className="operation update-operation" onSubmit={handleUpdate}>
              <div className="operation-heading">
                <p className="endpoint">PUT /api/students/{'{id}'}</p>
                <h2>Edit student #{activeStudent.id}</h2>
              </div>
              <StudentFields value={detailForm} onChange={setDetailForm} idPrefix="update" />
              <button type="submit" disabled={isUpdating}>
                {isUpdating ? 'Saving...' : 'Save changes'}
              </button>
            </form>
          )}
        </section>
      )}
    </main>
  )
}

export default App
