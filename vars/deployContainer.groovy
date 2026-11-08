// Reusable deploy step. In a company this would run `helm upgrade` or update an Argo CD repo.
// Here it replaces a local Docker container so the demo works on a laptop.
//   deployContainer(name: 'demo-app-dev', image: 'localhost:5000/demo-app:abc123-7', port: 8081, envName: 'DEV')

def call(Map args) {
  sh """
    docker pull ${args.image}
    docker rm -f ${args.name} || true
    docker run -d --name ${args.name} --restart unless-stopped \
      -p ${args.port}:8080 -e APP_ENV=${args.envName} ${args.image}
  """
  echo "${args.envName} is live: http://localhost:${args.port}  (image ${args.image})"
}
