export interface Student {
  id: number
  firstName: string
  lastName: string
  email: string
  program: string
}

export type StudentInput = Omit<Student, 'id'>

interface ApiProblem {
  detail?: string
  message?: string
}

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  })

  if (!response.ok) {
    const problem = await response.json().catch(() => ({} as ApiProblem))
    throw new Error(problem.detail ?? problem.message ?? `Request failed with status ${response.status}.`)
  }

  return response.json() as Promise<T>
}

export function createStudent(student: StudentInput) {
  return request<Student>('/students', {
    method: 'POST',
    body: JSON.stringify(student),
  })
}

export function getStudent(studentId: number) {
  return request<Student>(`/students/${studentId}`)
}

export function listStudents() {
  return request<Student[]>('/students')
}

export function updateStudent(studentId: number, student: StudentInput) {
  return request<Student>(`/students/${studentId}`, {
    method: 'PUT',
    body: JSON.stringify(student),
  })
}