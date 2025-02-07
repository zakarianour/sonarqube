pipeline {
    agent any

    environment {
        SONARQUBE_URL = 'http://127.0.0.1:8080'  // Replace with your SonarQube URL
    }

    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/ThisHobbit/sonarqube.git', branch: 'master'
            }
        }

        stage('Build') {
            steps {
                sh './gradlew clean build'
            }
        }

        stage('Unit Tests') {
            steps {
                sh './gradlew test'
            }
        }
    }
}
