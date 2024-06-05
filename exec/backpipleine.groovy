pipeline {
    agent any
    
    environment {
        imageName = "joonbum0113/a705-backend"
        registryCredential = 'dockerhub_a705'
        dockerImage = ''
        
        releaseServerAccount = 'ubuntu'
        releaseServerUri = 'i10a705.p.ssafy.io'
        releasePort = '8081'
    }
    
    stages {
        stage('Git Clone') {
            steps {
                git branch: 'develop',
                    credentialsId: 'gitlab-a705',
                    url: 'https://lab.ssafy.com/s10-webmobile1-sub2/S10P12A705.git'
            }
        }

        stage('check and Build with Gradle') {
            steps {
                script {

                        def gitDiffResult = sh(script: "git diff --name-only HEAD^ HEAD", returnStdout: true).trim()
                        println("gitDiffResult: " + gitDiffResult)
                    
                        def egrepResult = sh(script: "echo '${gitDiffResult}' | egrep '(\\.java|\\.gradle|\\.properties|\\.yml)\$' || true", returnStdout: true).trim()
                        println("egrepResult: " + egrepResult)
     
                        def services = sh(script: "echo '${egrepResult}' | cut -d/ -f1 | uniq", returnStdout: true).trim().split("\n")
                        println("services: " + services)

                        println("serviceslength: " + services.length)

                        def change = services[0]

                        println("change: " + change)
                        if (change == null || change == "front" || change == "" || change == " ") {
                            // services[0]에 값이 있을 때의 추가 작업 수행
                            echo "Change found: ${change}"
                            echo "No services to build. Exiting pipeline."
                            currentBuild.result = 'SUCCESS'
                            error("nothing to build. done")
                            
                        }else{
                            for (service in services) {
                                dir(service) {
                                    sh "chmod +x gradlew" // 실행 권한 추가
                                    sh "./gradlew build"
                                }
                            }
                        }
                }
            }
        }

        stage('Image Build & DockerHub Push') {
            steps {
                dir('server') {
                    script {
                        docker.withRegistry('', registryCredential) {
                            sh "docker buildx create --use --name mybuilder"
                            sh "docker buildx build --platform linux/amd64,linux/arm64 -t $imageName:$BUILD_NUMBER --push ."
                            sh "docker buildx build --platform linux/amd64,linux/arm64 -t $imageName:latest --push ."
                        }
                    }
                }
            }
        }

        stage('Before Service Stop') {
            steps {
                sshagent(credentials: ['ubuntu_a705']) {
                    sh '''
                    if test "`ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri "sudo docker ps -aq --filter ancestor=$imageName:latest"`"; then
                    ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri "sudo docker stop $(docker ps -aq --filter ancestor=$imageName:latest)"
                    ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri "sudo docker rm -f $(docker ps -aq --filter ancestor=$imageName:latest)"
                    ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri "sudo docker rmi $imageName:latest"
                    fi
                    '''
                }
            }
        }
        stage('DockerHub Pull') {
            steps {
                sshagent(credentials: ['ubuntu_a705']) {
                    sh "ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri 'sudo docker pull $imageName:latest'"
                }
            }
        }
        stage('Service Start') {
            steps {
                sshagent(credentials: ['ubuntu_a705']) {
                    sh '''
                        ssh -o StrictHostKeyChecking=no $releaseServerAccount@$releaseServerUri "sudo docker run -i -e TZ=Asia/Seoul -e "SPRING_PROFILES_ACTIVE=prod" --name haebang -p $releasePort:$releasePort -d $imageName:latest"
                    '''
                }
            }
        }
        stage('Service Check') {
            steps {
                sshagent(credentials: ['ubuntu_a705']) {
                    sh '''
                        #!/bin/bash
                        
                        for retry_count in \$(seq 20)
                        do
                          if curl -s "http://i10a705.p.ssafy.io:8081" > /dev/null
                          then
                              curl -d '{"text":":cat_clap: Back Release Complete :cat_clap:"}' -H "Content-Type: application/json" -X POST https://meeting.ssafy.com/hooks/tj7coo5cgi8oddxzqexh669a6e
                              break
                          fi
                        
                          if [ $retry_count -eq 20 ]
                          then
                            curl -d '{"text":":sad_pepe:  Back 릴리즈 실패! 실행시 문제가 생겼다..! :sad_pepe:"}' -H "Content-Type: application/json" -X POST https://meeting.ssafy.com/hooks/tj7coo5cgi8oddxzqexh669a6e
                            exit 1
                          fi
                        
                          echo "The server is not alive yet. Retry health check in 5 seconds..."
                          sleep 5
                        done
                    '''
                }
            }
        }
    }
}